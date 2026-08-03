package app.zhijuan.provider.stream

class MalformedStreamException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
