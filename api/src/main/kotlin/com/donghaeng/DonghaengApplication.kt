package com.donghaeng

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * [ConfigurationPropertiesScan] rather than an `@EnableConfigurationProperties`
 * list on whichever `@Configuration` happens to be nearby: a property class
 * belongs to the domain package that reads it, and a central registration list is
 * a second place to remember — one that only fails at runtime, in the environment
 * that set the property.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class DonghaengApplication

fun main(args: Array<String>) {
    runApplication<DonghaengApplication>(*args)
}
