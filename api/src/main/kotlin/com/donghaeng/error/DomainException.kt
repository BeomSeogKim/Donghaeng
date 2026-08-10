package com.donghaeng.error

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

abstract class DomainException(
    val code: String,
    status: HttpStatus,
    detail: String,
) : ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null) {
    init {
        body.setProperty(CODE, code)
    }

    companion object {
        const val CODE = "code"
    }
}
