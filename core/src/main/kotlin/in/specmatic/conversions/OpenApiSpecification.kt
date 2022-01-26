package `in`.specmatic.conversions

import `in`.specmatic.core.*
import `in`.specmatic.core.Result.Failure
import `in`.specmatic.core.pattern.*
import `in`.specmatic.core.value.NumberValue
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.core.value.Value
import `in`.specmatic.core.wsdl.parser.message.MULTIPLE_ATTRIBUTE_VALUE
import `in`.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import `in`.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE
import io.cucumber.messages.types.Step
import io.ktor.util.reflect.*
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.PathParameter
import io.swagger.v3.oas.models.parameters.QueryParameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import java.io.File

class OpenApiSpecification(private val openApiFile: String, val openApi: OpenAPI) : IncludedSpecification {
    companion object {
        fun fromFile(openApiFilePath: String, relativeTo: String): OpenApiSpecification {
            val openApiFile = File(openApiFilePath).let { openApiFile ->
                if (openApiFile.isAbsolute) {
                    openApiFile
                } else {
                    File(relativeTo).canonicalFile.parentFile.resolve(openApiFile)
                }
            }

            val openApiYAMLContent = openApiFile.readText()

            return OpenApiSpecification(
                openApiFile.canonicalPath,
                OpenAPIV3Parser().readContents(openApiYAMLContent).openAPI
            )
        }

        fun fromFile(openApiFile: String): OpenApiSpecification {
            val parseOptions = ParseOptions();
            parseOptions.isResolve = true;
            val openApi = OpenAPIV3Parser().read(openApiFile, null, parseOptions)
            return OpenApiSpecification(openApiFile, openApi)
        }

        fun fromYAML(yamlContent: String, filePath: String): OpenApiSpecification {
            val openApi = OpenAPIV3Parser().readContents(yamlContent).openAPI
            return OpenApiSpecification(filePath, openApi)
        }
    }

    val patterns = mutableMapOf<String, Pattern>()

    fun toFeature(): Feature {
        val name = File(openApiFile).name
        return Feature(toScenarioInfos().map { Scenario(it) }, name = name, path = openApiFile)
    }

    override fun toScenarioInfos(): List<ScenarioInfo> {
        val scenarioInfosWithExamples = toScenarioInfosWithExamples()
        return openApitoScenarioInfos().filter { scenarioInfo ->
            scenarioInfosWithExamples.none { scenarioInfoWithExample ->
                scenarioInfoWithExample.matchesSignature(scenarioInfo)
            }
        }.plus(scenarioInfosWithExamples)
    }

    override fun matches(
        specmaticScenarioInfo: ScenarioInfo,
        steps: List<Step>
    ): List<ScenarioInfo> {
        val openApiScenarioInfos = openApitoScenarioInfos()
        if (openApiScenarioInfos.isNullOrEmpty() || !steps.isNotEmpty()) return listOf(specmaticScenarioInfo)
        val result: MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> =
            specmaticScenarioInfo to openApiScenarioInfos to
                    ::matchesPath then
                    ::matchesMethod then
                    ::matchesStatus then
                    ::updateUrlMatcher otherwise
                    ::handleError
        when (result) {
            is MatchFailure -> throw ContractException(result.error.message)
            is MatchSuccess -> return result.value.second
        }
    }

    private fun matchesPath(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        val matchingScenarioInfos = openApiScenarioInfos.filter {
            it.httpRequestPattern.urlMatcher!!.matches(
                specmaticScenarioInfo.httpRequestPattern.generate(
                    Resolver()
                ), Resolver()
            ).isTrue()
        }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" PATH: "${
                        specmaticScenarioInfo.httpRequestPattern.urlMatcher!!.generatePath(Resolver())
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )
            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    private fun matchesMethod(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        val matchingScenarioInfos =
            openApiScenarioInfos.filter { it.httpRequestPattern.method == specmaticScenarioInfo.httpRequestPattern.method }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" METHOD: "${
                        specmaticScenarioInfo.httpRequestPattern.method
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )
            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    private fun matchesStatus(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        val matchingScenarioInfos =
            openApiScenarioInfos.filter { it.httpResponsePattern.status == specmaticScenarioInfo.httpResponsePattern.status }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" RESPONSE STATUS: "${
                        specmaticScenarioInfo.httpResponsePattern.status
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )
            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    private fun updateUrlMatcher(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        return MatchSuccess(specmaticScenarioInfo to openApiScenarioInfos.map {
            val queryPattern = it.httpRequestPattern.urlMatcher?.queryPattern ?: emptyMap()
            val urlMatcher = specmaticScenarioInfo.httpRequestPattern.urlMatcher?.copy(queryPattern = queryPattern)
            val httpRequestPattern = it.httpRequestPattern.copy(urlMatcher = urlMatcher)
            it.copy(httpRequestPattern = httpRequestPattern)
        })
    }

    private fun openApitoScenarioInfos(): List<ScenarioInfo> {
        return openApi.paths.map { (openApiPath, pathItem) ->
            openApiOperations(pathItem).map { (httpMethod, operation) ->
                val specmaticPath = toSpecmaticPath(openApiPath, operation)

                toHttpResponsePatterns(operation.responses).map { (response, responseMediaType, httpResponsePattern) ->
                    toHttpRequestPatterns(
                        specmaticPath,
                        httpMethod,
                        operation
                    ).map { httpRequestPattern: HttpRequestPattern ->
                        val scenarioName =
                            """Open API - Operation Summary: ${operation.summary}. Response: ${response.description}"""
                        scenarioInfo(scenarioName, httpRequestPattern, httpResponsePattern, patterns = this.patterns)
                    }
                }.flatten()
            }.flatten()
        }.flatten()
    }

    private fun toScenarioInfosWithExamples(): List<ScenarioInfo> {
        return openApi.paths.map { (openApiPath, pathItem) ->
            openApiOperations(pathItem).map { (httpMethod, operation) ->
                val specmaticPath = toSpecmaticPath(openApiPath, operation)

                toHttpResponsePatterns(operation.responses).map { (response, responseMediaType, httpResponsePattern) ->
                    val responseExamples = responseMediaType.examples.orEmpty()
                    val specmaticExampleRows: List<Row> = responseExamples.map { (exampleName, _) ->
                        val requestExamples =
                            operation.parameters.orEmpty()
                                .filter { parameter -> parameter.examples.orEmpty().any { it.key == exampleName } }
                                .map { it.name to it.examples[exampleName]!!.value }.toMap()

                        requestExamples.map { (key, value) -> key to value.toString() }.toList().isNotEmpty()

                        when {
                            requestExamples.isNotEmpty() -> Row(
                                requestExamples.keys.toList(),
                                requestExamples.values.toList().map { it.toString() })
                            else -> Row()
                        }
                    }

                    specmaticExampleRows.map { specmaticExampleRow: Row ->
                        toHttpRequestPatterns(
                            specmaticPath,
                            httpMethod,
                            operation
                        ).map { httpRequestPattern: HttpRequestPattern ->
                            val scenarioName =
                                """Open API - Operation Summary: ${operation.summary}. Response: ${response.description} Examples: ${specmaticExampleRow.stringForOpenAPIError()}"""
                            scenarioInfo(
                                scenarioName,
                                httpRequestPattern,
                                httpResponsePattern,
                                specmaticExampleRow,
                                emptyMap()
                            )
                        }
                    }.flatten()
                }.flatten()
            }.flatten()
        }.flatten()
    }

    private fun scenarioInfo(
        scenarioName: String,
        httpRequestPattern: HttpRequestPattern,
        httpResponsePattern: HttpResponsePattern,
        specmaticExampleRow: Row = Row(),
        patterns: Map<String, Pattern>
    ) = ScenarioInfo(
        scenarioName = scenarioName,
        patterns = patterns,
        httpRequestPattern = when (specmaticExampleRow.columnNames.isEmpty()) {
            true -> httpRequestPattern
            else -> httpRequestPattern.newBasedOn(specmaticExampleRow, Resolver())[0]
        },
        httpResponsePattern = when (specmaticExampleRow.columnNames.isEmpty()) {
            true -> httpResponsePattern
            else -> httpResponsePattern.newBasedOn(specmaticExampleRow, Resolver())[0]
        }
    )

    private fun toHttpResponsePatterns(responses: ApiResponses?): List<Triple<ApiResponse, MediaType, HttpResponsePattern>> {
        return responses.orEmpty().map { (status, response) ->
            val headersMap = response.headers.orEmpty().map { (headerName, header) ->
                toSpecmaticParamName(header.required != true, headerName) to toSpecmaticPattern(header.schema, emptyList())
            }.toMap()
            when (val content = response.content) {
                null -> listOf(
                    Triple(
                        response, MediaType(), HttpResponsePattern(
                            headersPattern = HttpHeadersPattern(headersMap),
                            status = when (status) {
                                "default" -> 400
                                else -> status.toInt()
                            }
                        )
                    )
                )
                else -> content.map { (contentType, mediaType) ->
                    Triple(
                        response, mediaType, HttpResponsePattern(
                            headersPattern = HttpHeadersPattern(
                                headersMap
                            ),
                            status = when (status) {
                                "default" -> 400
                                else -> status.toInt()
                            },
                            body = when(contentType) {
                                "application/xml" -> toXMLPattern(mediaType)
                                else -> toSpecmaticPattern(mediaType)
                            }
                        )
                    )
                }
            }
        }.flatten()
    }

    fun toHttpRequestPatterns(path: String, httpMethod: String, operation: Operation): List<HttpRequestPattern> {

        val parameters = operation.parameters

        val headersMap = parameters.orEmpty().filterIsInstance(HeaderParameter::class.java).map {
            toSpecmaticParamName(it.required != true, it.name) to toSpecmaticPattern(it.schema, emptyList())
        }.toMap()

        val urlMatcher = toURLMatcherWithOptionalQueryParams(path)
        val headersPattern = HttpHeadersPattern(headersMap)
        val requestPattern = HttpRequestPattern(
            urlMatcher = urlMatcher,
            method = httpMethod,
            headersPattern = headersPattern
        )

        return when (operation.requestBody) {
            null -> listOf(
                requestPattern
            )
            else -> operation.requestBody.content.map { (contentType, mediaType) ->
                when(contentType.lowercase()) {
                    "application/x-www-form-urlencoded" -> {
                        requestPattern.copy(formFieldsPattern = toFormFields(mediaType))
                    }
                    "application/xml" -> {
                        requestPattern.copy(body = toXMLPattern(mediaType))
                    }
                    else -> {
                        requestPattern.copy(body = toSpecmaticPattern(mediaType))
                    }
                }
            }
        }
    }

    private fun toFormFields(mediaType: MediaType) =
        mediaType.schema.properties.map { (formFieldName, formFieldValue) ->
            formFieldName to toSpecmaticPattern(
                formFieldValue, emptyList(),
                jsonInFormData = isJsonInString(mediaType, formFieldName)
            )
        }.toMap()

    private fun isJsonInString(
        mediaType: MediaType,
        formFieldName: String?
    ) = if (mediaType.encoding.isNullOrEmpty()) false
    else mediaType.encoding[formFieldName]?.contentType == "application/json"

    fun toSpecmaticPattern(mediaType: MediaType, jsonInFormData: Boolean = false): Pattern =
        toSpecmaticPattern(mediaType.schema, emptyList(), jsonInFormData = jsonInFormData)

    fun toSpecmaticPattern(
        schema: Schema<*>,
        typeStack: List<String>,
        patternName: String = "",
        jsonInFormData: Boolean = false
    ): Pattern {
        val pattern = when (schema) {
            is StringSchema -> when (schema.enum) {
                null -> StringPattern(minLength = schema.minLength, maxLength = schema.maxLength)
                else -> toEnum(schema, patternName) { enumValue -> StringValue(enumValue.toString()) }
            }
            is IntegerSchema -> when (schema.enum) {
                null -> NumberPattern()
                else -> toEnum(schema, patternName) { enumValue -> NumberValue(enumValue.toString().toInt()) }
            }
            is NumberSchema -> NumberPattern()
            is UUIDSchema -> StringPattern()
            is DateTimeSchema -> DateTimePattern
            is DateSchema -> StringPattern()
            is BooleanSchema -> BooleanPattern
            is ObjectSchema -> {
                if(schema.additionalProperties != null) {
                    toDictionaryPattern(schema, typeStack, patternName)
                } else if(schema.xml?.name != null) {
                    toXMLPattern(schema, typeStack = typeStack)
                } else {
                    toJsonObjectPattern(schema, patternName, typeStack)
                }
            }
            is ArraySchema -> {
                if(schema.xml?.name != null) {
                    toXMLPattern(schema, typeStack = typeStack)
                } else {

                    JSONArrayPattern(listOf(toSpecmaticPattern(schema.items, typeStack)))
                }
            }
            is ComposedSchema -> {
                if(schema.allOf != null) {
                    val schemaProperties = schema.allOf.map { constituentSchema ->
                        val schemaToProcess = if (constituentSchema.`$ref` != null) {
                            val (_, referredSchema) = resolveReferenceToSchema(constituentSchema.`$ref`)
                            referredSchema
                        } else constituentSchema

                        val requiredFields = schemaToProcess.required.orEmpty()
                        toSchemaProperties(schemaToProcess, requiredFields, patternName, typeStack)
                    }.fold(emptyMap<String, Pattern>()) { acc, entry -> acc.plus(entry) }
                    val jsonObjectPattern = toJSONObjectPattern(schemaProperties, "(${patternName})")
                    patterns["(${patternName})"] = jsonObjectPattern
                    jsonObjectPattern
                } else if (nullableOneOf(schema)) {
                    AnyPattern(listOf(NullPattern, toSpecmaticPattern(nonNullSchema(schema), typeStack, patternName)))
                } else {
                    throw UnsupportedOperationException("Specmatic does not support anyOf. Only allOf is supported, or oneOf for specifying nullable refs.")
                }
            }
            else -> {
                if (schema.additionalProperties != null) {
                    toDictionaryPattern(schema, typeStack, patternName)
                } else {
                    val component: String? = schema.`$ref`

                    when {
                        component != null -> {
                            val (componentName, referredSchema) = resolveReferenceToSchema(component)
                            val cyclicReference = patternName.isNotEmpty()
                                    && patternName == componentName
                                    && typeStack.contains(patternName)
                                    && referredSchema.instanceOf(ObjectSchema::class)
                            when {
                                cyclicReference -> DeferredPattern("(${patternName})")
                                else -> resolveReference(component, typeStack)
                            }
                        }
                        else -> toJsonObjectPattern(schema, patternName, typeStack)
                    }
                }
            }
        }.also {
            when {
                it.instanceOf(JSONObjectPattern::class) && jsonInFormData -> {
                    PatternInStringPattern(
                        patterns.getOrDefault("($patternName)", StringPattern()),
                        "($patternName)"
                    )
                }
                else -> it
            }
        }

        return when (schema.nullable != true) {
            true -> pattern
            else -> when (pattern) {
                is AnyPattern -> pattern
                else -> AnyPattern(listOf(NullPattern, pattern))
            }
        }
    }

    private fun toXMLPattern(mediaType: MediaType): Pattern {
        return toXMLPattern(mediaType.schema, typeStack = emptyList())
    }

    private fun toXMLPattern(schema: Schema<Any>, nodeNameFromProperty: String? = null, typeStack: List<String>): Pattern {
        val name = schema.xml?.name ?: nodeNameFromProperty

        return when(schema) {
            is ObjectSchema -> {
                val nodeProperties = schema.properties.filter { entry ->
                    entry.value.xml?.attribute != true
                }

                val nodes = nodeProperties.map { (propertyName: String, propertySchema) ->
                    val type = when (propertySchema.type) {
                        in primitiveOpenAPITypes -> {
                            val innerPattern = DeferredPattern(primitiveOpenAPITypes.getValue(propertySchema.type))
                            XMLPattern(XMLTypeData(propertyName, propertyName, emptyMap(), listOf(innerPattern)))
                        }
                        else -> {
                            toXMLPattern(propertySchema, propertyName, typeStack)
                        }
                    }

                    val optionalAttribute = if(propertyName !in (schema.required ?: emptyList<String>()))
                        mapOf(OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(OPTIONAL_ATTRIBUTE_VALUE)))
                    else
                        emptyMap()

                    if(type is XMLPattern)
                        type.copy(pattern = type.pattern.copy(attributes = optionalAttribute.plus(type.pattern.attributes)))
                    else
                        type
                }

                val attributeProperties = schema.properties.filter { entry ->
                    entry.value.xml?.attribute == true
                }

                val attributes: Map<String, Pattern> = attributeProperties.map { (name, schema) ->
                    name to toSpecmaticPattern(schema, emptyList())
                }.toMap()

                name ?: throw ContractException("Could not determine name for an xml node")

                val namespaceAttributes: Map<String, ExactValuePattern> = if(schema.xml?.namespace != null && schema.xml?.prefix != null) {
                    val attributeName = "xmlns:${schema.xml?.prefix}"
                    val attributeValue = ExactValuePattern(StringValue(schema.xml.namespace))
                    mapOf(attributeName to attributeValue)
                } else {
                    emptyMap()
                }

                val xmlTypeData = XMLTypeData(name, realName(schema, name), namespaceAttributes.plus(attributes), nodes)

                XMLPattern(xmlTypeData)
            }
            is ArraySchema -> {
                val repeatingSchema = schema.items

                val repeatingType = when(repeatingSchema.type) {
                    in primitiveOpenAPITypes -> {
                        val innerPattern = DeferredPattern(primitiveOpenAPITypes.getValue(repeatingSchema.type))

                        val innerName = repeatingSchema.xml?.name ?: if(schema.xml?.name != null && schema.xml?.wrapped == true) schema.xml.name else nodeNameFromProperty

                        XMLPattern(XMLTypeData(innerName ?: throw ContractException("Could not determine name for an xml node"), innerName, emptyMap(), listOf(innerPattern)))
                    }
                    else -> {
                        toXMLPattern(repeatingSchema, name, typeStack)
                    }
                }.let { repeatingType ->
                    if(repeatingType is XMLPattern)
                        repeatingType.copy(
                            pattern = repeatingType.pattern.copy(
                                attributes = repeatingType.pattern.attributes.plus(
                                    OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(MULTIPLE_ATTRIBUTE_VALUE))
                                )
                            )
                        )
                    else
                        repeatingType
                }

                if(schema.xml?.wrapped == true) {
                    val wrappedName = schema.xml?.name ?: nodeNameFromProperty
                    val wrapperTypeData = XMLTypeData(wrappedName ?: throw ContractException("Could not determine name for an xml node"), wrappedName, emptyMap(), listOf(repeatingType))
                    XMLPattern(wrapperTypeData)
                } else
                    repeatingType
            }
            else -> {
                if(schema.`$ref` != null) {
                    val component = schema.`$ref`
                    val (componentName, componentSchema) = resolveReferenceToSchema(component)

                    val typeName = "($componentName)"

                    val nodeName = componentSchema.xml?.name ?: name ?: componentName

                    if(typeName !in typeStack)
                        this.patterns[typeName] = toXMLPattern(componentSchema, componentName, typeStack.plus(typeName))

                    val xmlRefType = XMLTypeData(nodeName, nodeName, mapOf(TYPE_ATTRIBUTE_NAME to ExactValuePattern(
                        StringValue(
                            componentName
                        ))), emptyList())

                    XMLPattern(xmlRefType)
                } else
                    throw ContractException("Node not recognized as XML type: ${schema.type}")
            }
        }
    }

    private fun realName(schema: ObjectSchema, name: String): String =
        if (schema.xml?.prefix != null) {
            "${schema.xml?.prefix}:${name}"
        } else {
            name
        }

    private val primitiveOpenAPITypes = mapOf("string" to "(string)", "number" to "(number)", "integer" to "(number)", "boolean" to "(boolean)")

    private fun toDictionaryPattern(
        schema: Schema<*>,
        typeStack: List<String>,
        patternName: String
    ): DictionaryPattern {
        val valueSchema = schema.additionalProperties as Schema<Any>

        if(valueSchema.`$ref` == null)
            throw ContractException("Inline dictionaries not yet supported")

        return DictionaryPattern(
            StringPattern(),
            toSpecmaticPattern(valueSchema, typeStack, valueSchema.`$ref`, false)
        )
    }

    private fun nonNullSchema(schema: ComposedSchema): Schema<Any> {
        return schema.oneOf.first { it.nullable != true }
    }

    private fun nullableOneOf(schema: ComposedSchema): Boolean {
        return schema.oneOf.find { it.nullable != true } != null
    }

    private fun toJsonObjectPattern(
        schema: Schema<*>,
        patternName: String,
        typeStack: List<String>
    ): JSONObjectPattern {
        val requiredFields = schema.required.orEmpty()
        val schemaProperties = toSchemaProperties(schema, requiredFields, patternName, typeStack)
        val jsonObjectPattern = toJSONObjectPattern(schemaProperties, "(${patternName})")
        patterns["(${patternName})"] = jsonObjectPattern
        return jsonObjectPattern
    }

    private fun toSchemaProperties(
        schema: Schema<*>,
        requiredFields: List<String>,
        patternName: String,
        typeStack: List<String>
    ) = schema.properties.orEmpty().map { (propertyName, propertyType) ->
        val optional = !requiredFields.contains(propertyName)
        if (patternName.isNotEmpty()) {
            if (typeStack.contains(patternName) &&
                (propertyType.`$ref`.orEmpty().endsWith(patternName)
                || (propertyType is ArraySchema && propertyType.items?.`$ref`?.endsWith(patternName) == true))
            ) toSpecmaticParamName(
                optional,
                propertyName
            ) to DeferredPattern("(${patternName})")
            else {
                val specmaticPattern = toSpecmaticPattern(
                    propertyType,
                    typeStack.plus(patternName),
                    patternName
                )
                toSpecmaticParamName(optional, propertyName) to specmaticPattern
            }
        } else {
            toSpecmaticParamName(optional, propertyName) to toSpecmaticPattern(
                propertyType,
                typeStack
            )
        }
    }.toMap()

    private fun toEnum(schema: Schema<*>, patternName: String, toSpecmaticValue: (Any) -> Value) =
        AnyPattern(schema.enum.map<Any, Pattern> { enumValue ->
            when (enumValue) {
                null -> NullPattern
                else -> ExactValuePattern(toSpecmaticValue(enumValue))
            }
        }.toList(), typeAlias = patternName).also { if (patternName.isNotEmpty()) patterns["(${patternName})"] = it }

    private fun toSpecmaticParamName(optional: Boolean, name: String) = when (optional) {
        true -> "${name}?"
        false -> name
    }

    private fun resolveReference(component: String, typeStack: List<String>): Pattern {
        val (componentName, referredSchema) = resolveReferenceToSchema(component)
        return toSpecmaticPattern(referredSchema, typeStack, patternName = componentName)
    }

    private fun resolveReferenceToSchema(component: String): Pair<String, Schema<Any>> {
        if (!component.startsWith("#")) throw UnsupportedOperationException("Specmatic only supports local component references.")
        val componentName = component.removePrefix("#/components/schemas/")
        val schema = openApi.components.schemas[componentName] ?: ObjectSchema().also { it.properties = emptyMap() }

        return componentName to schema as Schema<Any>
    }

    private fun toSpecmaticPath(openApiPath: String, operation: Operation): String {
        val parameters = operation.parameters ?: return openApiPath

        var specmaticPath = openApiPath
        parameters.filterIsInstance(PathParameter::class.java).map {
            specmaticPath = specmaticPath.replace(
                "{${it.name}}",
                "(${it.name}:${toSpecmaticPattern(it.schema, emptyList()).typeName})"
            )
        }

        val queryParameters = parameters.filterIsInstance(QueryParameter::class.java).joinToString("&") {
            val specmaticPattern = toSpecmaticPattern(schema = it.schema, typeStack = emptyList(), patternName = it.name)
            val patternName = when {
                it.schema.enum != null -> specmaticPattern.run { "($typeAlias)" }
                else -> specmaticPattern
            }
            "${it.name}=$patternName"
        }

        if (queryParameters.isNotEmpty()) specmaticPath = "${specmaticPath}?${queryParameters}"

        return specmaticPath
    }

    private fun openApiOperations(pathItem: PathItem): Map<String, Operation> =
        mapOf<String, Operation?>(
            "GET" to pathItem.get,
            "POST" to pathItem.post,
            "DELETE" to pathItem.delete,
            "PUT" to pathItem.put
        ).filter { (_, value) -> value != null }.map { (key, value) -> key to value!! }.toMap()
}