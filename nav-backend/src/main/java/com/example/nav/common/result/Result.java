package com.example.nav.common.result;

public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static Result<Void> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static Result<Void> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
