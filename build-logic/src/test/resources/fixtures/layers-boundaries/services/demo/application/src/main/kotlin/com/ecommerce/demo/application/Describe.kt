package com.ecommerce.demo.application

import com.ecommerce.demo.domain.isBlankLabel

fun describe(value: String): String = if (isBlankLabel(value)) "blank" else value
