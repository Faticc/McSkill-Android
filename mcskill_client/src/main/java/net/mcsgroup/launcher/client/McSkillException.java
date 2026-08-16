package net.mcsgroup.launcher.client;

import io.grpc.Status;

public class McSkillException extends RuntimeException {
    public enum ErrorCode {
        NETWORK_UNAVAILABLE,
        UNAUTHENTICATED,
        INVALID_CREDENTIALS,
        MFA_REQUIRED,
        UNKNOWN
    }

    private final ErrorCode errorCode;

    public McSkillException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public McSkillException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public static McSkillException fromStatus(Status status) {
        ErrorCode code;
        switch (status.getCode()) {
            case UNAVAILABLE:
            case DEADLINE_EXCEEDED:
                code = ErrorCode.NETWORK_UNAVAILABLE;
                break;
            case UNAUTHENTICATED:
                code = ErrorCode.UNAUTHENTICATED;
                break;
            case INVALID_ARGUMENT:
            case PERMISSION_DENIED:
                code = ErrorCode.INVALID_CREDENTIALS;
                break;
            default:
                code = ErrorCode.UNKNOWN;
        }
        return new McSkillException(code, status.getDescription(), status.getCause());
    }
}
