import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/services.dart';
import 'package:libphonenumber_platform_interface/libphonenumber_platform_interface.dart';

/// A wrapper class [PhoneNumberUtil] that basically switch between plugin available for `Web` or `Android or IOS` and `Other platforms` when available.
class PhoneNumberUtil {
  static LibPhoneNumberPlatform get _platform => LibPhoneNumberPlatform.instance;

  /// The channel the Android and iOS implementations of this package serve.
  ///
  /// [getNumbersDetails] talks to it directly rather than through
  /// [LibPhoneNumberPlatform] because the batch call is specific to those two
  /// implementations; platforms without it keep working through the fallback
  /// below, so the platform interface needs no new member.
  static const MethodChannel _channel = MethodChannel('plugin.libphonenumber');

  /// [isValidPhoneNumber] checks if a [phoneNumber] is valid.
  /// Accepts [phoneNumber] and [isoCode]
  /// Returns [Future<bool>].
  static Future<bool?> isValidPhoneNumber(String phoneNumber, String isoCode) async {
    try {
      return await _platform.isValidPhoneNumber(phoneNumber, isoCode);
    } catch (_) {
      return false;
    }
  }

  /// [normalizePhoneNumber] normalizes a string of characters representing a phone number
  /// Accepts [phoneNumber] and [isoCode]
  /// Returns [Future<String>]
  static Future<String?> normalizePhoneNumber(String phoneNumber, String isoCode) async {
    return await _platform.normalizePhoneNumber(phoneNumber, isoCode);
  }

  /// [getRegionInfo] about phone number
  /// Accepts [phoneNumber] and [isoCode]
  /// Returns [Future<RegionInfo>] of all information available about the [phoneNumber]
  static Future<RegionInfo> getRegionInfo(String phoneNumber, String isoCode) async {
    try {
      Map<String, dynamic>? response = await _platform.getRegionInfo(phoneNumber, isoCode);
      return RegionInfo.fromJson(response);
    } catch (e) {
      return RegionInfo();
    }
  }

  /// [getNumberType] get type of phone number
  /// Accepts [phoneNumber] and [isoCode]
  /// Returns [Future<PhoneNumberType>] type of phone number
  static Future<PhoneNumberType> getNumberType(String phoneNumber, String isoCode) async {
    int? index = await _platform.getNumberType(phoneNumber, isoCode);
    return PhoneNumberType.fromIndex(index);
  }

  /// [formatAsYouType] uses Google's libphonenumber input format as you type.
  /// Accepts [phoneNumber] and [isoCode]
  /// Returns [Future<String>]
  static Future<String?> formatAsYouType(String phoneNumber, String isoCode) async {
    try {
      return await _platform.formatAsYouType(phoneNumber, isoCode);
    } catch (e) {
      return null;
    }
  }

  /// [getAllCountries] Returns all regions the library has metadata for.
  static Future<List<String>?> getAllCountries() async {
    return await _platform.getAllCountries();
  }

  /// [getFormattedExampleNumber] Gets a valid number for the specified region, number type and number format.
  /// Accepts [isoCode], [PhoneNumberType], [PhoneNumberFormat]
  static Future<String?> getFormattedExampleNumber(
    String isoCode,
    PhoneNumberType type,
    PhoneNumberFormat format,
  ) async {
    return await _platform.getFormattedExampleNumber(isoCode, type, format);
  }

  static Future<ParsedPhoneNumber?> parsePhoneNumber(String phoneNumber, String isoCode) async {
    return await _platform.parsePhoneNumber(phoneNumber, isoCode);
  }

  /// Resolves several phone numbers in a single platform call.
  ///
  /// Each entry of [numbers] holds a `phoneNumber` and an `isoCode`. The result
  /// has one entry per input, in the same order, with the keys `e164`,
  /// `isoCode`, `regionCode`, `national` and `international`. A number that
  /// could not be parsed carries an `error` key instead, so one malformed
  /// number never fails the rest of the batch.
  ///
  /// Resolving an address book number by number costs three round trips each —
  /// [normalizePhoneNumber], [getRegionInfo] and [formatAsYouType] — and parses
  /// the same number three times. This call parses each number once natively
  /// and returns every representation in one response, which turns thousands of
  /// messages into one.
  ///
  /// Falls back to the single-number methods on platforms that have no batch
  /// implementation, and when the native side of this package predates it.
  static Future<List<Map<String, String?>>> getNumbersDetails(
    List<Map<String, String>> numbers,
  ) async {
    if (numbers.isEmpty) {
      return const <Map<String, String?>>[];
    }
    if (!kIsWeb) {
      try {
        final response = await _channel.invokeListMethod<Map<Object?, Object?>>(
          'getNumbersDetails',
          <String, dynamic>{'numbers': numbers},
        );
        // A short response would silently misalign numbers with their details,
        // so anything unexpected goes down the fallback instead.
        if (response != null && response.length == numbers.length) {
          return response
              .map(
                (details) => details.map((key, value) => MapEntry(key as String, value as String?)),
              )
              .toList(growable: false);
        }
      } on MissingPluginException {
        // Native side does not implement the batch call yet.
      } on PlatformException {
        // Never let a platform-level failure take down the whole address book:
        // the fallback still resolves every number it can.
      }
    }
    return _numbersDetailsFallback(numbers);
  }

  static Future<List<Map<String, String?>>> _numbersDetailsFallback(
    List<Map<String, String>> numbers,
  ) async {
    final details = <Map<String, String?>>[];
    for (final number in numbers) {
      final phoneNumber = number['phoneNumber'] ?? '';
      final isoCode = number['isoCode'] ?? '';
      try {
        final regionInfo = await getRegionInfo(phoneNumber, isoCode);
        details.add(<String, String?>{
          'e164': await normalizePhoneNumber(phoneNumber, isoCode),
          'isoCode': regionInfo.isoCode,
          'regionCode': regionInfo.regionPrefix,
          'national': regionInfo.formattedPhoneNumber,
          'international': await formatAsYouType(phoneNumber, isoCode),
        });
      } catch (e) {
        details.add(<String, String?>{'error': '$e'});
      }
    }
    return details;
  }
}
