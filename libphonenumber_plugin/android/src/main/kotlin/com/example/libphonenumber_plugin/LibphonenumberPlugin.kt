package com.example.libphonenumber_plugin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.StandardMethodCodec
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.HashMap

/**
 * LibphonenumberPlugin
 */
class LibphonenumberPlugin : FlutterPlugin, MethodCallHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private var channel: MethodChannel? = null

  /// Application context, needed only by [handleAddressBookRegionCounts] to
  /// reach the contacts provider. Never an Activity, so it is safe to hold.
  private var context: Context? = null

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPluginBinding) {
    // Serve this channel on a background task queue instead of the platform
    // thread. Every method below runs libphonenumber synchronously inside the
    // handler, and on Android the platform thread is the same thread that
    // drives the UI, so resolving an address book of several hundred numbers
    // used to stall frame production for as long as the work took.
    //
    // Nothing here touches an Activity or a View, so there is no reason for
    // the work to sit on the UI thread; the contacts provider query is a
    // blocking read that belongs off it. The shared `phoneUtil` is documented
    // as thread safe and `AsYouTypeFormatter` is created per call, so a
    // background queue introduces no shared mutable state.
    context = flutterPluginBinding.applicationContext
    val messenger = flutterPluginBinding.binaryMessenger
    channel = MethodChannel(
      messenger,
      "plugin.libphonenumber",
      StandardMethodCodec.INSTANCE,
      messenger.makeBackgroundTaskQueue(),
    )
    channel!!.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
    channel!!.setMethodCallHandler(null)
    context = null
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "isValidPhoneNumber" -> handleIsValidPhoneNumber(call, result)
      "normalizePhoneNumber" -> handleNormalizePhoneNumber(call, result)
      "getRegionInfo" -> handleGetRegionInfo(call, result)
      "getNumberType" -> handleGetNumberType(call, result)
      "formatAsYouType" -> handleFormatAsYouType(call, result)
      "getAllCountries" -> handleGetAllCountries(result)
      "getFormattedExampleNumber" -> handleGetFormattedExampleNumber(call, result)
      "parse" -> handleParse(call, result)
      "getNumbersDetails" -> handleGetNumbersDetails(call, result)
      "addressBookRegionCounts" -> handleAddressBookRegionCounts(call, result)
      else -> result.notImplemented()
    }
  }

  /**
   * Counts the address book's contacts per region of their FIRST phone
   * number, without handing a single number to Dart.
   *
   * The engager `ab_countries` ranking needs only "how many contacts have
   * their first number in each country". Deriving that from a full address
   * book fetch costs the whole book (every property group) travelling to Dart
   * and every number a platform round trip to be parsed. Here the phone table
   * is read with three columns, the first number of each contact is resolved
   * to its region with libphonenumber on this background queue, and only the
   * counts travel back (BAT-9824).
   *
   * Mirrors the Dart ranking's input number by number, so both yield the same
   * top countries (the Dart side checks parity in debug builds):
   * - the first number is the primary one, then the lowest data id — the
   *   order flutter_contacts presents them in;
   * - the string is the provider's E.164 normalisation when it has one, else
   *   the raw number; Arabic-Indic digits become ASCII, everything but digits
   *   and a leading `+` is dropped, `*` and `#` end the number;
   * - a digit-led string without an exit code (00, 011, …) is taken as
   *   international and gets a `+`; one with an exit code is parsed under the
   *   caller's `isoCode` — the user's country, as the Dart fallback pass does —
   *   which is how `011 212 …` resolves to Morocco for a US user;
   * - a number libphonenumber parses but assigns no region falls back to the
   *   main region of its calling code, the closest native equivalent of the
   *   Dart dial-code table.
   *
   * Expects `isoCode`: the region for numbers without `+` (may be empty).
   * Answers null without READ_CONTACTS, or when the provider fails, so the
   * caller keeps its fallback. Otherwise a map with `counts` (lowercase ISO
   * region → contacts) and `contacts` (how many first numbers were parsed).
   */
  private fun handleAddressBookRegionCounts(call: MethodCall, result: MethodChannel.Result) {
    val context = this.context
    if (context == null || !hasReadContactsPermission(context)) {
      result.success(null)
      return
    }
    val region = call.argument<String>("isoCode")
      ?.trim()
      ?.uppercase(Locale.ROOT)
      ?.takeIf { it.isNotEmpty() }
      ?: UNKNOWN_REGION
    try {
      val startNanos = System.nanoTime()
      val firstNumbers = firstPhoneNumberPerContact(context)
      val counts = HashMap<String, Int>()
      for (iso in regionsOf(firstNumbers, region)) {
        counts[iso] = (counts[iso] ?: 0) + 1
      }
      val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
      // Counts and timing only, never the numbers themselves (PII, DCS-5281).
      Log.d(
        TAG,
        "addressBookRegionCounts: ${firstNumbers.size} contacts, ${counts.size} regions in ${elapsedMs}ms",
      )
      result.success(mapOf("counts" to counts, "contacts" to firstNumbers.size))
    } catch (e: Exception) {
      // A provider failure must never take the caller down: null keeps its fallback.
      Log.d(TAG, "addressBookRegionCounts failed: ${e.javaClass.simpleName}")
      result.success(null)
    }
  }

  private fun hasReadContactsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
      context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

  /**
   * The first phone number of every contact, as the string the ranking parses.
   *
   * One query over the phone table, ordered so that the first row of each
   * contact is its primary number, then its oldest one — the same "first
   * number" flutter_contacts hands to Dart.
   */
  private fun firstPhoneNumberPerContact(context: Context): List<String> {
    val projection = arrayOf(Phone.CONTACT_ID, Phone.NORMALIZED_NUMBER, Phone.NUMBER)
    val order = "${Phone.CONTACT_ID} ASC, ${Phone.IS_PRIMARY} DESC, ${Phone._ID} ASC"
    val numbers = ArrayList<String>()
    val cursor = context.contentResolver.query(Phone.CONTENT_URI, projection, null, null, order)
      ?: return numbers
    cursor.use { c ->
      val contactColumn = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
      val normalizedColumn = c.getColumnIndexOrThrow(Phone.NORMALIZED_NUMBER)
      val rawColumn = c.getColumnIndexOrThrow(Phone.NUMBER)
      var lastContact: String? = null
      while (c.moveToNext()) {
        val contact = c.getString(contactColumn)
        if (contact == lastContact) continue // only the first number of a contact counts
        lastContact = contact
        val number = parseInput(c.getString(normalizedColumn), c.getString(rawColumn)) ?: continue
        numbers.add(number)
      }
    }
    return numbers
  }

  /**
   * The string the Dart pipeline hands libphonenumber for this number, or null
   * when it hands nothing (no digits at all).
   *
   * Dart keeps the provider's normalisation as the number's msisdn and parses
   * that, else the raw number; its pre-parse keeps digits and a leading `+`;
   * its `addPlusIfMissing` then takes a digit-led string without an exit code
   * as international. A string with an exit code stays as is and is parsed
   * under the user's region.
   */
  private fun parseInput(normalized: String?, raw: String?): String? {
    val source = normalized?.takeIf { it.isNotBlank() } ?: raw ?: return null
    val stripped = stripToMsisdn(source)
    if (stripped.isEmpty()) return null
    if (stripped.startsWith("+")) return stripped
    return if (EXIT_CODES.any { stripped.startsWith(it) }) stripped else "+$stripped"
  }

  /**
   * Digits and a leading `+`; `*` and `#` end the number; Arabic-Indic digits
   * fold to ASCII. The Dart side's `idtm_stringByRemovingNonMSISDNCharacters`
   * after its `replaceArabicNumber`.
   */
  private fun stripToMsisdn(value: String): String {
    val out = StringBuilder(value.length)
    for ((index, c) in value.withIndex()) {
      when {
        c == '*' || c == '#' -> break
        c == '+' && index == 0 -> out.append(c)
        c in '0'..'9' -> out.append(c)
        c in '٠'..'٩' -> out.append('0' + (c - '٠'))
      }
    }
    return out.toString()
  }

  /** Regions of [numbers] (region-less ones dropped), spread across [batchExecutor]. */
  private fun regionsOf(numbers: List<String>, region: String): List<String> {
    if (numbers.size < PARALLEL_BATCH_MINIMUM) {
      return numbers.mapNotNull { regionOf(it, region) }
    }
    val tasks = numbers.chunked(REGION_CHUNK_SIZE).map { chunk ->
      Callable { chunk.mapNotNull { regionOf(it, region) } }
    }
    return batchExecutor.invokeAll(tasks).flatMap { future -> future.get() }
  }

  /**
   * Lowercase ISO region of [number] parsed under [region] (ignored by
   * libphonenumber for a number with `+`), or null when it cannot be parsed.
   * A parsed number with no region falls back to its calling code's main
   * region, like the Dart dial-code table.
   */
  private fun regionOf(number: String, region: String): String? {
    val parsed = try {
      phoneUtil.parse(number, region)
    } catch (e: Exception) {
      return null // NumberParseException: no region, as in Dart
    }
    val iso = try {
      phoneUtil.getRegionCodeForNumber(parsed)
    } catch (e: Exception) {
      null // the NPE it can raise for a number with no known region
    } ?: phoneUtil.getRegionCodeForCountryCode(parsed.countryCode).takeIf { it != UNKNOWN_REGION }
    return iso?.lowercase(Locale.ROOT)
  }

  /**
   * Resolves a whole list of phone numbers in a single platform call.
   *
   * A caller that normalises an address book needs the E.164 form, the region
   * and the display formats of every number. Asking for those one method at a
   * time costs three round trips per number and parses the same number three
   * times. Here each number is parsed once and every representation travels
   * back in one response, so the cost of the whole address book is one message
   * instead of thousands.
   *
   * Expects `numbers`: a list of maps holding `phoneNumber` and `isoCode`.
   * Returns one map per input, in the same order. A number that cannot be
   * parsed yields a map holding only `error`, so a single malformed contact
   * never fails the batch — unlike the single-number methods, which report a
   * parse failure as a channel-level error.
   */
  private fun handleGetNumbersDetails(call: MethodCall, result: MethodChannel.Result) {
    val numbers = call.argument<List<Map<String, String>>>("numbers") ?: emptyList()
    val startNanos = System.nanoTime()
    val details = numbersDetails(numbers)
    val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
    // Counts and timing only, never the numbers themselves (PII, DCS-5281).
    // This is the native half of the BAT-9823 timing: a Dart-side stopwatch
    // around the channel call minus this value is the codec, task-queue and
    // event-loop overhead of the platform round trip.
    val unparsed = details.count { it.containsKey("error") }
    Log.d(TAG, "getNumbersDetails: ${numbers.size} numbers in ${elapsedMs}ms ($unparsed unparsed)")
    result.success(details)
  }

  /**
   * Resolves [numbers] spread across [batchExecutor] instead of sequentially:
   * parsing an address book of thousands of numbers takes whole seconds of
   * CPU, and on one thread that time is the wall time of the batch call.
   *
   * `invokeAll` returns the futures in task order, so the response still
   * carries one map per input in the same order. [numberDetails] catches its
   * own exceptions into an `error` entry, so the futures never fail.
   */
  private fun numbersDetails(numbers: List<Map<String, String>>): List<Map<String, String?>> {
    if (numbers.size < PARALLEL_BATCH_MINIMUM) {
      return numbers.map { number -> numberDetails(number["phoneNumber"], number["isoCode"]) }
    }
    val tasks = numbers.map { number ->
      Callable { numberDetails(number["phoneNumber"], number["isoCode"]) }
    }
    return batchExecutor.invokeAll(tasks).map { future -> future.get() }
  }

  /**
   * Every representation of [phoneNumber] that the single-number methods can
   * produce, derived from one parse.
   *
   * Formatting an already parsed number is an in-memory operation, so the
   * extra representations are free compared to the parse itself.
   */
  private fun numberDetails(phoneNumber: String?, isoCode: String?): Map<String, String?> {
    val details = HashMap<String, String?>()
    try {
      val p = phoneUtil.parse(phoneNumber, isoCode)
      details["e164"] = phoneUtil.format(p, PhoneNumberFormat.E164)
      details["isoCode"] = phoneUtil.getRegionCodeForNumber(p)
      details["regionCode"] = p.countryCode.toString()
      details["national"] = phoneUtil.format(p, PhoneNumberFormat.NATIONAL)
      details["international"] = phoneUtil.format(p, PhoneNumberFormat.INTERNATIONAL)
    } catch (e: Exception) {
      // Covers NumberParseException as well as the NPE getRegionCodeForNumber
      // can raise for a number with no known region.
      details["error"] = e.message ?: e.javaClass.simpleName
    }
    return details
  }

  private fun handleParse(call: MethodCall, result: MethodChannel.Result) {
    val phoneNumber = call.argument<String>("phoneNumber")
    val isoCode = call.argument<String>("isoCode")
    try {
      val p = phoneUtil.parse(phoneNumber, isoCode);
      val phoneNumberMap = HashMap<String, String>();
      phoneNumberMap.put("countryCode", p.countryCode.toString());
      phoneNumberMap.put("nationalNumber", p.nationalNumber.toString());
      val normalized = phoneUtil.format(p, PhoneNumberFormat.E164);
      phoneNumberMap.put("e164Format", normalized);
      result.success(phoneNumberMap);
    } catch (e: NumberParseException) {
      result.error("NumberParseException", e.message, null);
    }
  }

  private fun handleIsValidPhoneNumber(call: MethodCall, result: MethodChannel.Result) {
    val phoneNumber = call.argument<String>("phoneNumber")
    val isoCode = call.argument<String>("isoCode")
    try {
      val p = phoneUtil.parse(phoneNumber, isoCode)
      result.success(phoneUtil.isValidNumber(p))
    } catch (e: NumberParseException) {
      result.error("NumberParseException", e.message, null)
    }
  }

  private fun handleNormalizePhoneNumber(call: MethodCall, result: MethodChannel.Result) {
    val phoneNumber = call.argument<String>("phoneNumber")
    val isoCode = call.argument<String>("isoCode")
    try {
      val p = phoneUtil.parse(phoneNumber, isoCode)
      val normalized = phoneUtil.format(p, PhoneNumberFormat.E164)
      result.success(normalized)
    } catch (e: NumberParseException) {
      result.error("NumberParseException", e.message, null)
    }
  }

  private fun handleGetRegionInfo(call: MethodCall, result: MethodChannel.Result) {
    val phoneNumber = call.argument<String>("phoneNumber")
    val isoCode = call.argument<String>("isoCode")
    try {
      val p = phoneUtil.parse(phoneNumber, isoCode)
      val regionCode = phoneUtil.getRegionCodeForNumber(p)
      val countryCode = p.countryCode.toString()
      val formattedNumber = phoneUtil.format(p, PhoneNumberFormat.NATIONAL)
      val resultMap: MutableMap<String, String> = HashMap()
      resultMap["isoCode"] = regionCode
      resultMap["regionCode"] = countryCode
      resultMap["formattedPhoneNumber"] = formattedNumber
      result.success(resultMap)
    } catch (e: NumberParseException) {
      result.error("NumberParseException", e.message, null)
    } catch (e: Exception) {
      result.error("UnexpectedException", e.message, null)
    }
  }

  private fun handleGetNumberType(call: MethodCall, result: MethodChannel.Result) {
    val phoneNumber = call.argument<String>("phoneNumber")
    val isoCode = call.argument<String>("isoCode")
    try {
      val p = phoneUtil.parse(phoneNumber, isoCode)
      val t = phoneUtil.getNumberType(p)
      val index = getIndexForPhoneNumberType(t)
      result.success(index)
    } catch (e: NumberParseException) {
      result.error("NumberParseException", e.message, null)
    }
  }

  private fun handleFormatAsYouType(call: MethodCall, result: MethodChannel.Result) {
    val phoneNumber = call.argument<String>("phoneNumber")
    val isoCode = call.argument<String>("isoCode")
    val asYouTypeFormatter = phoneUtil.getAsYouTypeFormatter(isoCode)
    var data: String? = null
    for (i in 0 until (phoneNumber?.length ?: 0)) {
      data = asYouTypeFormatter.inputDigit(phoneNumber!![i])
    }
    result.success(data)
  }

  private fun handleGetAllCountries(result: MethodChannel.Result) {
    val allCountries: List<String> = ArrayList(phoneUtil.supportedRegions).sorted()
    result.success(allCountries)
  }

  private fun handleGetFormattedExampleNumber(call: MethodCall, result: MethodChannel.Result) {
    val isoCode = call.argument<String>("isoCode")
    val type = call.argument<Int>("type")!!
    val format = call.argument<Int>("format")!!
    val phoneNumberType = getPhoneNumberTypeForIndex(type)
    val phoneNumberFormat = getPhoneNumberFormatForIndex(format)
    val exampleNumber = phoneUtil.getExampleNumberForType(isoCode, phoneNumberType)
    val formattedPhoneNumber = phoneUtil.format(exampleNumber, phoneNumberFormat)
    result.success(formattedPhoneNumber)
  }

  private fun getPhoneNumberFormatForIndex(index: Int): PhoneNumberFormat {
    return when (index) {
      1 -> PhoneNumberFormat.INTERNATIONAL
      2 -> PhoneNumberFormat.NATIONAL
      3 -> PhoneNumberFormat.RFC3966
      0 -> PhoneNumberFormat.E164
      else -> PhoneNumberFormat.E164
    }
  }

  private fun getPhoneNumberTypeForIndex(index: Int): PhoneNumberType {
    return when (index) {
      0 -> PhoneNumberType.FIXED_LINE
      1 -> PhoneNumberType.MOBILE
      2 -> PhoneNumberType.FIXED_LINE_OR_MOBILE
      3 -> PhoneNumberType.TOLL_FREE
      4 -> PhoneNumberType.PREMIUM_RATE
      5 -> PhoneNumberType.SHARED_COST
      6 -> PhoneNumberType.VOIP
      7 -> PhoneNumberType.PERSONAL_NUMBER
      8 -> PhoneNumberType.PAGER
      9 -> PhoneNumberType.UAN
      10 -> PhoneNumberType.VOICEMAIL
      else -> PhoneNumberType.UNKNOWN
    }
  }

  private fun getIndexForPhoneNumberType(type: PhoneNumberType): Int {
    return when (type) {
      PhoneNumberType.FIXED_LINE -> 0
      PhoneNumberType.MOBILE -> 1
      PhoneNumberType.FIXED_LINE_OR_MOBILE -> 2
      PhoneNumberType.TOLL_FREE -> 3
      PhoneNumberType.PREMIUM_RATE -> 4
      PhoneNumberType.SHARED_COST -> 5
      PhoneNumberType.VOIP -> 6
      PhoneNumberType.PERSONAL_NUMBER -> 7
      PhoneNumberType.PAGER -> 8
      PhoneNumberType.UAN -> 9
      PhoneNumberType.VOICEMAIL -> 10
      else -> -1
    }
  }

  companion object {
    private const val TAG = "LibphonenumberPlugin"

    private val phoneUtil = PhoneNumberUtil.getInstance()

    /** Below this size a batch is resolved inline: pooling has no win to offer. */
    private const val PARALLEL_BATCH_MINIMUM = 64

    /** Numbers per worker task in [regionsOf]: one parse each, so coarse tasks cost nothing. */
    private const val REGION_CHUNK_SIZE = 128

    /**
     * libphonenumber's "unknown region": the parse region when the caller has
     * none, and what [PhoneNumberUtil.getRegionCodeForCountryCode] answers for
     * a calling code it does not know.
     */
    private const val UNKNOWN_REGION = "ZZ"

    /**
     * International exit codes the Dart side's `addPlusIfMissing` recognises:
     * a digit-led number starting with one is dialled, not international.
     */
    private val EXIT_CODES = listOf("00", "0011", "000", "009", "011")

    /**
     * Workers for [numbersDetails] batches. libphonenumber's PhoneNumberUtil
     * is documented thread safe, and the pool is bounded so an address-book
     * sized batch cannot starve the rest of the app of cores.
     */
    private val batchExecutor: ExecutorService = Executors.newFixedThreadPool(
      Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
    )
  }
}