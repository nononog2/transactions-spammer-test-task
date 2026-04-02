package com.example.platformcore.exception

class NotFoundException(message: String) : RuntimeException(message)

class OverloadedException(message: String) : RuntimeException(message)

class InvalidRequestException(message: String) : RuntimeException(message)
