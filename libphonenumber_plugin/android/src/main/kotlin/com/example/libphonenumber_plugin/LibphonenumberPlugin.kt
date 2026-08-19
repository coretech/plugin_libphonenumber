package com.example.libphonenumber_plugin

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
  override fun onAttachedToEngine(flutterPluginBinding: FlutterPluginBinding) {
    // Serve this channel on a background task queue instead of the platform
    // thread. Every method below runs libphonenumber synchronously inside the
    // handler, and on Android the platform thread is the same thread that
    // drives the UI, so resolving an address book of several hundred numbers
    // used to stall frame production for as long as the work took.
    //
    // Nothing here touches an Activity, a Context or a View, so there is no
    // reason for the work to sit on the UI thread. The shared `phoneUtil` is
    // documented as thread safe and `AsYouTypeFormatter` is created per call,
    // so a background queue introduces no shared mutable state.
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
      else -> result.notImplemented()
    }
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
    result.success(numbersDetails(numbers))
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
    private val phoneUtil = PhoneNumberUtil.getInstance()

    /** Below this size a batch is resolved inline: pooling has no win to offer. */
    private const val PARALLEL_BATCH_MINIMUM = 64

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