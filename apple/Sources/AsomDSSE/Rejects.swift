import AsomJSON

extension RejectCode {
    public static let tooLarge = RejectCode("TOO_LARGE")
    public static let containerVersionUnknown = RejectCode("CONTAINER_VERSION_UNKNOWN")
    public static let containerInvalid = RejectCode("CONTAINER_INVALID")
    public static let payloadTypeUnsupported = RejectCode("PAYLOAD_TYPE_UNSUPPORTED")
    public static let schemaMajorUnknown = RejectCode("SCHEMA_MAJOR_UNKNOWN")
    public static let signatureCount = RejectCode("SIGNATURE_COUNT")
    public static let signatureEncoding = RejectCode("SIGNATURE_ENCODING")
    public static let signatureInvalid = RejectCode("SIGNATURE_INVALID")
    public static let keyNotPinned = RejectCode("KEY_NOT_PINNED")
    public static let algUnsupported = RejectCode("ALG_UNSUPPORTED")
    public static let testOnlyKey = RejectCode("TEST_ONLY_KEY")
    public static let fingerprintMismatch = RejectCode("FINGERPRINT_MISMATCH")
}
