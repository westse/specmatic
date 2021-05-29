package `in`.specmatic.core.value

import `in`.specmatic.core.Result
import org.w3c.dom.Document
import org.w3c.dom.Node

sealed interface XMLValue: Value {
    fun build(document: Document): Node
    fun matchFailure(): Result.Failure
    fun addSchema(schema: XMLNode): XMLValue
}