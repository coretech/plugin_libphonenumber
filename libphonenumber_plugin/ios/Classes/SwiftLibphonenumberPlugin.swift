import Contacts
import Flutter
import UIKit
import PhoneNumberKit

public class SwiftLibphonenumberPlugin: NSObject, FlutterPlugin {

    let phoneNumberUtility = PhoneNumberUtility()

    /// The regions libphonenumber has metadata for.
    ///
    /// `allCountries()` builds and returns the whole list on every call, and
    /// the region check in `parsePhoneNumber` runs on every single parse, so
    /// resolving an address book used to rebuild this list once per number.
    /// Cache it once and look it up as a set.
    lazy var supportedRegions: Set<String> = Set(phoneNumberUtility.allCountries())

    public static func register(with registrar: FlutterPluginRegistrar) {
        // Serve this channel on a background task queue instead of the main
        // queue. Every method below runs PhoneNumberKit synchronously inside
        // the handler, so resolving an address book of several hundred numbers
        // used to occupy the thread that drives the UI for as long as the work
        // took. Nothing here touches UIKit, and the queue is serial, so the
        // shared plugin instance is never accessed concurrently.
        //
        // `makeBackgroundTaskQueue` is optional in the messenger protocol, so
        // a nil result simply keeps the previous main-queue behaviour.
        let messenger = registrar.messenger()
        let channel = FlutterMethodChannel(
            name: "plugin.libphonenumber",
            binaryMessenger: messenger,
            codec: FlutterStandardMethodCodec.sharedInstance(),
            taskQueue: messenger.makeBackgroundTaskQueue?()
        )

        let instance = SwiftLibphonenumberPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "isValidPhoneNumber":
            isValidPhoneNumber(call: call, result: result)
            break
        case "normalizePhoneNumber":
            normalizePhoneNumber(call: call, result: result)
            break
        case "getRegionInfo":
            getRegionInfo(call: call, result: result)
            break
        case "getNumberType":
            getNumberType(call: call, result: result)
            break
        case "formatAsYouType":
            formatAsYouType(call: call, result: result)
            break
        case "getAllCountries":
            getAllCountries(call: call, result: result)
            break
        case "getFormattedExampleNumber":
            getFormattedExampleNumber(call: call, result: result)
            break
        case "parse":
            parsePhoneNumber(call: call, result: result)
            break
        case "getNumbersDetails":
            getNumbersDetails(call: call, result: result)
            break
        case "addressBookRegionCounts":
            addressBookRegionCounts(call: call, result: result)
            break
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    /// Resolves a whole list of phone numbers in a single platform call.
    ///
    /// A caller that normalises an address book needs the E.164 form, the
    /// region and the display formats of every number. Asking for those one
    /// method at a time costs three round trips per number and parses the same
    /// number three times. Here each number is parsed once and every
    /// representation travels back in one response.
    ///
    /// Expects `numbers`: a list of maps holding `phoneNumber` and `isoCode`.
    /// Returns one map per input, in the same order. A number that cannot be
    /// parsed yields a map holding only `error`, so a single malformed contact
    /// never fails the batch.
    func getNumbersDetails(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? Dictionary<String, Any>
        let numbers = arguments?["numbers"] as? [[String: Any]] ?? []

        let details: [[String: String?]] = numbers.map { number in
            numberDetails(
                number["phoneNumber"] as? String ?? "",
                withRegion: number["isoCode"] as? String ?? ""
            )
        }

        result(details)
    }

    /// Every representation of [phoneNumber] that the single-number methods can
    /// produce, derived from one parse. Formatting an already parsed number is
    /// an in-memory operation, so the extra representations are free compared
    /// to the parse itself.
    private func numberDetails(_ phoneNumber: String, withRegion isoCode: String) -> [String: String?] {
        do {
            let p: PhoneNumber = try parsePhoneNumber(phoneNumber, withRegion: isoCode.uppercased(), ignoreType: true)
            let regionCode: String? = phoneNumberUtility.getRegionCode(of: p)
            let countryCode: String?

            if let prefix = phoneNumberUtility.countryCode(for: regionCode ?? "") {
                countryCode = String(prefix)
            } else {
                countryCode = nil
            }

            return [
                "e164": phoneNumberUtility.format(p, toType: PhoneNumberFormat.e164),
                "isoCode": regionCode,
                "regionCode": countryCode,
                "national": phoneNumberUtility.format(p, toType: PhoneNumberFormat.national),
                "international": phoneNumberUtility.format(p, toType: PhoneNumberFormat.international),
            ]
        } catch let error as NSError {
            return ["error": error.localizedDescription]
        }
    }

    /// Counts the address book's contacts per region of their FIRST phone
    /// number, without handing a single number to Dart (BAT-9824). The Android
    /// implementation documents the rationale; the two must stay in step.
    ///
    /// Mirrors the Dart ranking's input number by number: iOS has no
    /// provider-side E.164 normalisation, so the raw number is stripped to
    /// digits and a leading `+` (Arabic-Indic digits folded, `*`/`#` end it);
    /// a digit-led string without an exit code gets a `+`; one with an exit
    /// code is parsed under the caller's `isoCode`, the user's country, as the
    /// Dart fallback pass does; a parsed number with no region falls back to
    /// its calling code's main country. Answers nil without contacts access,
    /// or when the store fails, so the caller keeps its fallback. Otherwise
    /// `counts` (lowercase ISO region → contacts) and `contacts` (how many
    /// first numbers were parsed).
    func addressBookRegionCounts(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard hasContactsAccess() else {
            result(nil)
            return
        }
        let arguments = call.arguments as? Dictionary<String, Any>
        let region = (arguments?["isoCode"] as? String ?? "")
            .trimmingCharacters(in: .whitespaces)
            .uppercased()
        let request = CNContactFetchRequest(keysToFetch: [CNContactPhoneNumbersKey as CNKeyDescriptor])
        var counts: [String: Int] = [:]
        var contacts = 0
        do {
            try CNContactStore().enumerateContacts(with: request) { contact, _ in
                guard let number = self.parseInput(contact.phoneNumbers.first?.value.stringValue) else {
                    return
                }
                contacts += 1
                if let iso = self.regionOf(number, withRegion: region) {
                    counts[iso, default: 0] += 1
                }
            }
        } catch {
            result(nil)
            return
        }
        result(["counts": counts, "contacts": contacts])
    }

    /// Full access or, on iOS 18+, limited access: a limited book is still
    /// the book the Dart side sees, so the ranking stays consistent with it.
    private func hasContactsAccess() -> Bool {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        if status == .authorized {
            return true
        }
        if #available(iOS 18.0, *), status == .limited {
            return true
        }
        return false
    }

    /// International exit codes the Dart side's `addPlusIfMissing` recognises:
    /// a digit-led number starting with one is dialled, not international.
    private static let exitCodes = ["00", "0011", "000", "009", "011"]

    /// The string the Dart pipeline hands libphonenumber for this number, or
    /// nil when it hands nothing (no digits at all).
    private func parseInput(_ raw: String?) -> String? {
        guard let raw = raw else {
            return nil
        }
        let stripped = stripToMsisdn(raw)
        if stripped.isEmpty {
            return nil
        }
        if stripped.hasPrefix("+") {
            return stripped
        }
        return Self.exitCodes.contains(where: { stripped.hasPrefix($0) }) ? stripped : "+" + stripped
    }

    /// Digits and a leading `+`; `*` and `#` end the number; Arabic-Indic
    /// digits fold to ASCII. The Dart side's
    /// `idtm_stringByRemovingNonMSISDNCharacters` after `replaceArabicNumber`.
    private func stripToMsisdn(_ value: String) -> String {
        var out = ""
        for (index, c) in value.enumerated() {
            if c == "*" || c == "#" {
                break
            }
            if c == "+" && index == 0 {
                out.append(c)
            } else if let ascii = c.asciiValue, ascii >= 48, ascii <= 57 {
                out.append(c)
            } else if let scalar = c.unicodeScalars.first, scalar.value >= 0x0660, scalar.value <= 0x0669 {
                out.append(Character(UnicodeScalar(UInt8(48 + scalar.value - 0x0660))))
            }
        }
        return out
    }

    /// Lowercase ISO region of [number] parsed under [region] (ignored for a
    /// number with `+`), or nil when it cannot be parsed. A parsed number with
    /// no region falls back to its calling code's main country, like the Dart
    /// dial-code table.
    private func regionOf(_ number: String, withRegion region: String) -> String? {
        guard let parsed = try? parsePhoneNumber(number, withRegion: region) else {
            return nil
        }
        if let iso = phoneNumberUtility.getRegionCode(of: parsed) {
            return iso.lowercased()
        }
        return phoneNumberUtility.mainCountry(forCode: parsed.countryCode)?.lowercased()
    }

    func parsePhoneNumber(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as! Dictionary<String, Any>
        let phoneNumber = arguments["phoneNumber"] as! String
        let isoCode = arguments["isoCode"] as! String

        do {
            let p: PhoneNumber = try parsePhoneNumber(phoneNumber, withRegion: isoCode.uppercased())
            let formatted: String = phoneNumberUtility.format(p, toType: PhoneNumberFormat.e164)
            let data: Dictionary<String, String> = [
                "countryCode": String(p.countryCode),
                "nationalNumber": String(p.nationalNumber),
                "e164Format": formatted,

            ];
            result(data)
        } catch let error as NSError {
            result(FlutterError(code: "\(error.code)", message: error.localizedDescription, details: nil))
        }
    }

    func isValidPhoneNumber(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as! Dictionary<String, Any>
        let phoneNumber = arguments["phoneNumber"] as! String
        let isoCode = arguments["isoCode"] as! String

        let isValid: Bool = phoneNumberUtility.isValidPhoneNumber(phoneNumber, withRegion: isoCode.uppercased(), ignoreType: true)

        result(isValid)
    }


    func normalizePhoneNumber(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as! Dictionary<String, Any>
        let phoneNumber = arguments["phoneNumber"] as! String
        let isoCode = arguments["isoCode"] as! String

        do {
            let p: PhoneNumber = try parsePhoneNumber(phoneNumber, withRegion: isoCode.uppercased(), ignoreType: true)

            let normalized: String = phoneNumberUtility.format(p, toType: PhoneNumberFormat.e164)

            result(normalized)
        } catch let error as NSError {
            result(FlutterError(code: "\(error.code)", message: error.localizedDescription, details: nil))
        }
    }

    func getRegionInfo(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as! Dictionary<String, Any>
        let phoneNumber = arguments["phoneNumber"] as! String
        let isoCode = arguments["isoCode"] as! String

        do {

            let p: PhoneNumber = try parsePhoneNumber(phoneNumber, withRegion: isoCode.uppercased(), ignoreType: true)

            let regionCode: String? = phoneNumberUtility.getRegionCode(of: p)
            let countryCode: String?

            if let prefix = phoneNumberUtility.countryCode(for: regionCode ?? "") {
                countryCode = String(prefix)
            } else {
                countryCode = nil
            }

            let formattedNumber: String? = phoneNumberUtility.format(p, toType: PhoneNumberFormat.national)


            let data : Dictionary<String, String?> = ["isoCode": regionCode, "regionCode" : countryCode, "formattedPhoneNumber" : formattedNumber]

            result(data)
        } catch let error as NSError {
            result(FlutterError(code: "\(error.code)", message: error.localizedDescription, details: nil))
        }
    }

    func getNumberType(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as! Dictionary<String, Any>
        let phoneNumber = arguments["phoneNumber"] as! String
        let isoCode = arguments["isoCode"] as! String

        do {
            let p: PhoneNumber = try parsePhoneNumber(phoneNumber, withRegion: isoCode.uppercased(), ignoreType: false)

            let t: PhoneNumberType = p.type

            let index: Int = getIndexFor(phoneNumberType: t)

            result(index)
        } catch let error as NSError {
            result(FlutterError(code: "\(error.code)", message: error.localizedDescription, details: nil))
        }
    }


    func formatAsYouType(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as! Dictionary<String, Any>
        let phoneNumber = arguments["phoneNumber"] as! String
        let isoCode = arguments["isoCode"] as! String

        let partialFormatter: PartialFormatter = PartialFormatter(utility: phoneNumberUtility, defaultRegion: isoCode.uppercased())

        let formattedNumber = partialFormatter.formatPartial(phoneNumber)

        result(formattedNumber)
    }

    func getAllCountries(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let allCountries = phoneNumberUtility.allCountries().filter {
            $0.rangeOfCharacter(from: CharacterSet.letters.inverted) == nil
        }

        result(allCountries)
    }

    func getFormattedExampleNumber(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as! Dictionary<String, Any>

        let isoCode = arguments["isoCode"] as! String
        let type = arguments["type"] as! Int
        let format = arguments["format"] as! Int

        let phoneNumberType = getPhoneNumberTypeFor(index: type)

        let phoneNumberFormat = getPhoneNumberFormatFor(index: format)

        let formattedExampleNumber = phoneNumberUtility.getFormattedExampleNumber(forCountry: isoCode, ofType: phoneNumberType, withFormat: phoneNumberFormat, withPrefix: true)


        result(formattedExampleNumber)
    }
}

public extension SwiftLibphonenumberPlugin {

    private func parsePhoneNumber(_ phonenumber: String, withRegion regionCode: String, ignoreType: Bool = true) throws -> PhoneNumber {
        do {
            // `supportedRegions` is cached on the instance: this check runs on
            // every parse, and rebuilding the region list here made the cost of
            // resolving an address book grow with the number of contacts.
            if (regionCode.isEmpty == false && supportedRegions.contains(regionCode)) {
                return try phoneNumberUtility.parse(phonenumber, withRegion: regionCode, ignoreType: ignoreType)
            } else {
                return try phoneNumberUtility.parse(phonenumber, ignoreType: ignoreType)
            }
        } catch {
          throw error
        }
    }

    private func getIndexFor(phoneNumberFormat format: PhoneNumberFormat) -> Int {
        switch format {
        case .e164:
            return 0
        case .international:
            return 1
        case .national:
            return 2
        }
    }

    private func getPhoneNumberFormatFor(index format: Int) -> PhoneNumberFormat {
        switch format {
        case 0:
            return .e164
        case 1:
            return .international
        case 2:
            return .national
        default:
            return .e164
        }
    }

    private func getIndexFor(phoneNumberType type: PhoneNumberType) -> Int {
        switch type {
        case .fixedLine:
            return 0
        case .mobile:
            return 1
        case .fixedOrMobile:
            return 2
        case .tollFree:
            return 3
        case .premiumRate:
            return 4
        case .sharedCost:
            return 5
        case .voip:
            return 6
        case .personalNumber:
            return 7
        case .pager:
            return 8
        case .uan:
            return 9
        case .voicemail:
            return 10
        default:
            return -1
        }
    }

    private func getPhoneNumberTypeFor(index type: Int) -> PhoneNumberType {
        switch type {
        case 0:
            return .fixedLine
        case 1:
            return .mobile
        case 2:
            return .fixedOrMobile
        case 3:
            return .tollFree
        case 4:
            return .premiumRate
        case 5:
            return .sharedCost
        case 6:
            return .voip
        case 7:
            return .personalNumber
        case 8:
            return .pager
        case 9:
            return .unknown
        case 10:
            return .voicemail
        default:
            return .unknown
        }
    }
}
