package `in`.specmatic.conversions

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import `in`.specmatic.core.*
import `in`.specmatic.core.HttpRequest
import `in`.specmatic.core.log.Verbose
import `in`.specmatic.core.log.logger
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.pattern.parsedJSONObject
import `in`.specmatic.core.utilities.exceptionCauseMessage
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.NumberValue
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.core.value.Value
import `in`.specmatic.stub.HttpStub
import `in`.specmatic.stub.createStubFromContracts
import `in`.specmatic.test.TestExecutor
import io.ktor.http.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Ignore
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper
import org.testcontainers.shaded.okhttp3.MediaType.*
import org.testcontainers.shaded.okhttp3.OkHttpClient
import org.testcontainers.shaded.okhttp3.Request
import org.testcontainers.shaded.okhttp3.RequestBody
import java.io.File
import java.net.URI
import java.util.function.Consumer
import java.util.stream.Stream

internal class OpenApiKtTest {
    companion object {
        val openAPISpec = """
Feature: Hello world

Background:
  Given openapi openapi/hello.yaml            

Scenario: zero should return not found
  When GET /hello/0
  Then status 404
        """.trimIndent()

        private val sourceSpecPath = File("src/test/resources/hello.spec").canonicalPath

        @BeforeAll
        @JvmStatic
        fun setup() {
            logger = Verbose()
        }

        @JvmStatic
        fun multiPartFileUploadSpecs(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("openapi/helloMultipart.yaml", ".*"),
                Arguments.of("openapi/helloMultipartWithExamples.yaml", "input.txt"),
            )
        }
    }

    @Test
    fun `should create stub from gherkin that includes OpenAPI spec`() {
        val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

        val response = HttpStub(feature).use {
            val restTemplate = RestTemplate()
            restTemplate.exchange(
                URI.create("http://localhost:9000/hello/1"), HttpMethod.GET,
                httpEntityWithBearerAuthHeader(), String::class.java
            )
        }

        assertThat(response.statusCodeValue).isEqualTo(200)

        HttpStub(feature).use {
            val restTemplate = RestTemplate()
            try {
                restTemplate.exchange(
                    URI.create("http://localhost:9000/hello/0"),
                    HttpMethod.GET,
                    httpEntityWithBearerAuthHeader(),
                    String::class.java
                )
            } catch (e: HttpClientErrorException) {
                assertThat(e.statusCode).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND)
            }
        }
    }

    @Test
    fun `should create test from gherkin that includes OpenAPI spec`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["${request.path} executed"] = true
                    assertThat(request.path).matches("""\/hello\/[0-9]+""")
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val id = request.path!!.split('/')[2].toInt()
                    val status = when (id) {
                        0 -> 404
                        else -> 200
                    }
                    return HttpResponse(status, "", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["/hello/0 executed"]).isTrue
        assertThat(flags.size).isEqualTo(2)
        assertThat(results.report()).isEqualTo("""Match not found""".trimIndent())
    }

    @Test
    fun `should create tests from OpenAPI examples`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/helloWithExamples.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["${request.path} executed"] = true
                    assertThat(request.path).matches("""\/hello\/[0-9]+""")
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val id = request.path!!.split('/')[2].toInt()
                    val status = when (id) {
                        0 -> 404
                        else -> 200
                    }
                    return HttpResponse(status, "hello world", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["/hello/0 executed"]).isTrue
        assertThat(flags["/hello/15 executed"]).isTrue
        assertThat(flags.size).isEqualTo(2)
        assertThat(results.report()).isEqualTo("""Match not found""".trimIndent())
    }

    @Test
    fun `should create tests for indirect optional non-nullable cyclic reference`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/circular-reference-optional-non-nullable.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["${request.path} executed"] = true
                    assertThat(request.path).matches("""\/demo\/circular-reference-optional-non-nullable""")
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    return HttpResponse(200, """{"intermediate-node": {}}""", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["/demo/circular-reference-optional-non-nullable executed"]).isTrue
        assertThat(flags.size).isEqualTo(1)
        assertThat(results.report()).isEqualTo("""Match not found""".trimIndent())
    }

    @Test
    fun `should create tests for indirect nullable cyclic reference`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/circular-reference-nullable.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["${request.path} executed"] = true
                    assertThat(request.path).matches("""\/demo\/circular-reference-nullable""")
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    return HttpResponse(200, """{"contents": {"intermediate-node": {"indirect-cycle": null}}}""", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["/demo/circular-reference-nullable executed"]).isTrue
        assertThat(flags.size).isEqualTo(1)
        assertThat(results.report()).isEqualTo("""Match not found""".trimIndent())
    }

    @Test
    fun `should create tests for indirect polymorphic cyclic reference`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/circular-reference-polymorphic.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["${request.path} executed"] = true
                    assertThat(request.path).matches("""\/demo\/circular-reference-polymorphic""")
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    return HttpResponse(200, """{"myBase": {"@type": "MySub1", "aMyBase": {"@type": "MySub2", "myVal": "aVal"}}}""", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["/demo/circular-reference-polymorphic executed"]).isTrue
        assertThat(flags.size).isEqualTo(1)
        assertThat(results.report()).isEqualTo("""Match not found""".trimIndent())
    }

    @Test
    fun `should report errors in tests created from OpenAPI examples`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/helloWithExamples.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["${request.path} executed"] = true
                    assertThat(request.path).matches("""\/hello\/[0-9]+""")
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val id = request.path!!.split('/')[2].toInt()
                    val status = when (id) {
                        0 -> 403
                        else -> 202
                    }
                    return HttpResponse(status, "hello world", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["/hello/0 executed"]).isTrue
        assertThat(flags["/hello/15 executed"]).isTrue
        assertThat(flags.size).isEqualTo(2)
        assertThat(results.report()).isEqualTo("""Match not found""".trimIndent())
    }

    @Disabled
    @Test
    fun `should report error when the application accepts null or other data types for a non-nullable parameter`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-non-nullable-parameter.yaml
  
  Scenario: create pet
    When POST /pets
    Then status 201
    Examples:
      | tag     | name | optional |
      | testing | test | 99999999 |

        """.trimIndent(), sourceSpecPath
        )

        val results = feature.copy(generativeTestingEnabled = true).executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["${request.path} executed"] = true
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val petParameters = ObjectMapper().readValue(request.bodyString, Map::class.java)
                    if (petParameters["name"] == null) return HttpResponse(422, "name cannot be null", headers)
                    if (petParameters["optional"] != null) {
                        try {
                            Integer.parseInt(petParameters["optional"].toString())
                        } catch (numberFormatException: java.lang.NumberFormatException) {
                            flags["Non-numeric value sent for a number"] = true
                            throw numberFormatException
                        }
                    }
                    return HttpResponse(201, "hello world", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(results.results.size).isEqualTo(12)
        assertThat(results.results.filterIsInstance<Result.Success>().size).isEqualTo(4)
        assertThat(results.results.filterIsInstance<Result.Failure>().size).isEqualTo(8)
        assertThat(flags["Non-numeric value sent for a number"]).isTrue
    }

    @Test
    fun `should generate all combinations under positive scenarios`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-with-optional-and-nullable-parameters.yaml
  
  Scenario: create pet
    When POST /pets
    Then status 201
    Examples:
      | tag     | name |
      | testing | test |
        """.trimIndent(), sourceSpecPath
        )

        val results = try {
            System.setProperty(Flags.negativeTestingFlag, "true")

            feature.copy(generativeTestingEnabled = true).executeTests(
                object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        flags["${request.path} executed"] = true
                        val headers: HashMap<String, String> = object : HashMap<String, String>() {
                            init {
                                put("Content-Type", "application/json")
                            }
                        }
                        val petParameters = ObjectMapper().readValue(request.bodyString, Map::class.java)
                        return if (petParameters.size != 2 || petParameters.values.contains(null))
                            HttpResponse(422, "all keys and their values should be present", headers)
                        else
                            HttpResponse(201, "hello world", headers)
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }
            )
        } finally {
            System.clearProperty(Flags.negativeTestingFlag)
        }

        assertThat(results.results.size).isEqualTo(5)
        assertThat(results.results.filterIsInstance<Result.Success>().size).isEqualTo(1)
        assertThat(results.results.filterIsInstance<Result.Failure>().size).isEqualTo(4)
    }

    @Test
    fun `should report error when the application accepts null for a non-nullable parameter given example row with the entire request body`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-non-nullable-parameter.yaml
  
  Scenario: create pet
    When POST /pets
    Then status 201
    Examples:
      | (RESPONSE-BODY)                                                 |
      | {"tag": "testing", "name": "test", "optional": "test-optional"} |

        """.trimIndent(), sourceSpecPath
        )

        val results = feature.copy(generativeTestingEnabled = true).executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["${request.path} executed"] = true
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val petParameters = ObjectMapper().readValue(request.bodyString, Map::class.java)
                    if (petParameters["name"] == null) return HttpResponse(422, "name cannot be null", headers)
                    return HttpResponse(201, "hello world", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(results.results.size).isEqualTo(16)
        assertThat(results.results.filter { it is Result.Success }.size).isEqualTo(4)
        assertThat(results.results.filter { it is Result.Failure }.size).isEqualTo(12)
    }

    @Test
    fun `should report error in test with both OpenAPI and Gherkin scenario names`() {
        val flags = mutableMapOf<String, Boolean>()

        val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    flags["executed"] = true
                    assertThat(request.path).matches("""\/hello\/[0-9]+""")
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val id = request.path!!.split('/')[2].toInt()
                    val status = when (id) {
                        0 -> 403
                        else -> 202
                    }
                    return HttpResponse(status, "", headers)
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["executed"]).isTrue
        assertFalse(results.success())
        assertThat(results.report()).isEqualTo("""Match not found""".trimIndent())
    }

    @Test
    fun `should throw error when request in Gherkin scenario does not match included OpenAPI spec`() {
        assertThatThrownBy {
            parseGherkinStringToFeature(
                """
        Feature: Hello world
        
        Background:
          Given openapi openapi/hello.yaml            
        
        Scenario: sending string instead of number should return not found
          When GET /hello/test
          Then status 404
                """.trimIndent(), sourceSpecPath
            )
        }.satisfies(Consumer {
            val errorMessage = exceptionCauseMessage(it)
            assertThat(errorMessage).contains("""Error matching url /hello/test to the specification""")
            assertThat(errorMessage).contains("Expected number, actual was \"test\"")
        })
    }

    @Test
    fun `should throw error when response code in Gherkin scenario does not match included OpenAPI spec`() {
        assertThatThrownBy {
            parseGherkinStringToFeature(
                """
        Feature: Hello world
        
        Background:
          Given openapi openapi/hello.yaml            
        
        Scenario: zero should return forbidden
          When GET /hello/0
          Then status 403
                """.trimIndent(), sourceSpecPath
            )
        }.satisfies(Consumer {
            assertThat(it.message).isEqualTo("""Scenario: "zero should return forbidden" RESPONSE STATUS: "403" is not as per included wsdl / OpenApi spec""")
        })
    }

    @Test
    fun `should generate stub with non primitive open api data types`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val response = HttpStub(feature).use {
            val restTemplate = RestTemplate()
            restTemplate.exchange(URI.create("http://localhost:9000/pets/1"), HttpMethod.GET, null, Pet::class.java)
        }

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body).isInstanceOf(Pet::class.java)
    }

    @Test
    fun `should generate stub with non primitive array open api data types`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )
        val headers = HttpHeaders()
        headers.set("X-Request-ID", "717e5682-c214-11eb-8529-0242ac130003")
        val requestEntity: HttpEntity<String> = HttpEntity("", headers)
        listOf("http://localhost:9000/pets", "http://localhost:9000/pets?tag=test&limit=3").forEach { urlString ->
            val response = HttpStub(feature).use {
                val restTemplate = RestTemplate()
                restTemplate.exchange(
                    URI.create(urlString),
                    HttpMethod.GET,
                    requestEntity,
                    object : ParameterizedTypeReference<List<Pet>>() {}
                )
            }

            assertThat(response.statusCodeValue).isEqualTo(200)
            assertThat(response.body).isInstanceOf(List::class.java)
            assertThat(response.body[0]).isInstanceOf(Pet::class.java)
            assertThat(response.headers.keys).containsAll(
                listOf(
                    "Content-Type",
                    "X-RateLimit-Limit",
                    "X-RateLimit-Remaining",
                    "X-RateLimit-Reset"
                )
            )
        }

        HttpStub(feature).use {
            val restTemplate = RestTemplate()
            try {
                restTemplate.exchange(
                    URI.create("http://localhost:9000/pets?tag=test&limit=three"),
                    HttpMethod.GET,
                    requestEntity,
                    object : ParameterizedTypeReference<List<Pet>>() {}
                )
            } catch (e: HttpClientErrorException) {
                assertThat(e.statusCode).isEqualTo(BAD_REQUEST)
            }
        }
    }

    @Test
    fun `should not throw errors while parsing file with no paths`() {
        OpenApiSpecification.fromFile("openapi/common.yaml").toFeature()
    }

    @Test
    fun `should run zero tests when the file has no paths`() {
        val feature = OpenApiSpecification.fromFile("openapi/common.yaml").toFeature()
        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = HttpResponse(200)
                override fun setServerState(serverState: Map<String, Value>) {}
            },
            scenarioNames = emptyList()
        )
        assertThat(results.hasResults()).isFalse
    }

    @Test
    fun `should generate stub with primitive array open api data types`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val response = HttpStub(feature).use {
            val restTemplate = RestTemplate()
            restTemplate.exchange(
                URI.create("http://localhost:9000/petIds"),
                HttpMethod.GET,
                httpEntityWithBearerAuthHeader(),
                object : ParameterizedTypeReference<List<Integer>>() {}
            )
        }

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body).isInstanceOf(List::class.java)
        assertThat(response.body[0]).isInstanceOf(Integer::class.java)
    }

    private fun httpEntityWithBearerAuthHeader() = HttpEntity(null, HttpHeaders().also { it.setBearerAuth("test") })

    @Test
    fun `should generate stub that returns error when bearer auth security defined at operation level is not satisfied`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        HttpStub(feature).use {
            val restTemplate = RestTemplate()
            val httpClientErrorException = assertThrows<HttpClientErrorException> {
                restTemplate.exchange(
                    URI.create("http://localhost:9000/petIds"),
                    HttpMethod.GET,
                    null,
                    object : ParameterizedTypeReference<List<Integer>>() {}
                )
            }
            assertThat(httpClientErrorException.statusCode).isEqualTo(BAD_REQUEST)
        }
    }

    @Test
    fun `should generate stub that returns error when bearer auth security defined at document level is not satisfied`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/hello.yaml
        """.trimIndent(), sourceSpecPath
        )

        HttpStub(feature).use {
            val restTemplate = RestTemplate()
            val httpClientErrorException = assertThrows<HttpClientErrorException> {
                restTemplate.exchange(
                    URI.create("http://localhost:9000/hello/0"),
                    HttpMethod.GET,
                    null,
                    object : ParameterizedTypeReference<List<Integer>>() {}
                )
            }
            assertThat(httpClientErrorException.statusCode).isEqualTo(BAD_REQUEST)
        }
    }

    @Test
    fun `should generate stub that authenticates with api key in header and query`() {
        createStubFromContracts(listOf("src/test/resources/openapi/apiKeyAuth.yaml")).use {
            val requestWithHeader = HttpRequest(
                method = "GET",
                path = "/hello/10",
                headers = mapOf(
                    "X-API-KEY" to "test"
                )
            )

            val responseFromHeader = it.client.execute(requestWithHeader)
            assertThat(responseFromHeader.status).isEqualTo(200)

            val requestWithQuery = HttpRequest(
                method = "GET",
                path = "/hello/10",
                queryParams = mapOf(
                    "apiKey" to "test"
                )
            )

            val responseFromQuery = it.client.execute(requestWithQuery)
            assertThat(responseFromQuery.status).isEqualTo(200)
        }
    }

    @Test
    fun `should throw not supported error when a security scheme other than bearer auth is defined`() {
        assertThrows<ContractException> {
            parseGherkinStringToFeature(
                """
Feature: Hello world

Background:
  Given openapi openapi/unsupported-authentication.yaml
        """.trimIndent(), sourceSpecPath
            )
        }.also { assertThat(it.message).isEqualTo("Specmatic only supports bearer and api key authentication (header, query) security schemes at the moment") }
    }

    @Test
    fun `should throw not supported error when a non-query-or-header API security scheme is defined`() {
        assertThrows<ContractException> {
            parseGherkinStringToFeature(
                """
Feature: Hello world

Background:
  Given openapi openapi/apiKeyAuthCookie.yaml
        """.trimIndent(), sourceSpecPath
            )
        }.also { assertThat(it.message).isEqualTo("Specmatic only supports bearer and api key authentication (header, query) security schemes at the moment") }
    }

    @Test
    fun `should generate test with api key security scheme value from row`() {
        val contract: Feature = parseGherkinStringToFeature(
            """
Feature: Authenticated

  Background:
    Given openapi openapi/authenticated.yaml
  
  Scenario: Header auth test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | X-API-KEY | id |
    | abc123    | 10 |
        """.trimIndent(), sourceSpecPath
        )

        val contractTests = contract.generateContractTestScenarios(emptyList())
        val result = executeTest(contractTests.single(), object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers).containsEntry("X-API-KEY", "abc123")
                return HttpResponse.OK("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }

        })

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @ParameterizedTest
    @MethodSource("multiPartFileUploadSpecs")
    fun `should generate test with multipart file upload`(openApiFile: String, fileName: String) {
        val contract: Feature = parseGherkinStringToFeature(
            """
Feature: multipart file upload

  Background:
    Given openapi $openApiFile
        """.trimIndent(), sourceSpecPath
        )

        val contractTests = contract.generateContractTestScenarios(emptyList())
        val result = executeTest(contractTests.single(), object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val multipartFileValues = request.multiPartFormData.filterIsInstance<MultiPartFileValue>()
                assertThat(multipartFileValues.size).isEqualTo(1)
                assertThat(multipartFileValues.first().name).isEqualTo("fileName")
                assertThat(multipartFileValues.first().filename).matches(fileName)
                return HttpResponse.OK("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }

        })

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should generate stub that accepts file upload data`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/helloMultipart.yaml
        """.trimIndent(), sourceSpecPath
        )

        HttpStub(feature).use {
            val restTemplate = RestTemplate()
            val body: MultiValueMap<String, Any> = LinkedMultiValueMap()
            body.add("orderId", 1)
            body.add("userId", 2)
            val filePair: MultiValueMap<String, String> = LinkedMultiValueMap()
            val contentDisposition = ContentDisposition
                .builder("form-data")
                .name("fileName")
                .filename("input.txt")
                .build()
            filePair.add(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
            val fileEntity = HttpEntity("test".toByteArray(), filePair)
            body.add("fileName", fileEntity)
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            val requestEntity = HttpEntity(body, headers)
            val response: ResponseEntity<String> = restTemplate
                .postForEntity(URI.create("http://localhost:9000/hello"), requestEntity, String::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        }
    }

    @Test
    fun `should generate stub that that returns error when multipart content is not a file`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/helloMultipart.yaml
        """.trimIndent(), sourceSpecPath
        )

        HttpStub(feature).use {
            val restTemplate = RestTemplate()
            val body: MultiValueMap<String, Any> = LinkedMultiValueMap()
            body.add("orderId", 1)
            body.add("userId", 2)
            body.add("fileName", "not a file")
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            val requestEntity = HttpEntity(body, headers)
            val httpClientErrorException = assertThrows<HttpClientErrorException> {
                restTemplate
                    .postForEntity(URI.create("http://localhost:9000/hello"), requestEntity, String::class.java)
            }
            assertThat(httpClientErrorException.message).contains("The contract expected a file, but got content instead.")
        }
    }

    @Test
    fun `should generate test with bearer auth security scheme value from row`() {
        val contract: Feature = parseGherkinStringToFeature(
            """
Feature: Authenticated

  Background:
    Given openapi openapi/authenticated.yaml
  
  Scenario: Bearer auth test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | Authorization | id |
    | Bearer abc123 | 10 |
        """.trimIndent(), sourceSpecPath
        )

        val contractTests = contract.generateContractTestScenarios(emptyList())
        val result = executeTest(contractTests.single(), object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers).containsEntry("Authorization", "Bearer abc123")
                return HttpResponse.OK("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }

        })

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should generate test with query param api key auth security scheme value from row`() {
        val contract: Feature = parseGherkinStringToFeature(
            """
Feature: Authenticated

  Background:
    Given openapi openapi/authenticated.yaml
  
  Scenario: Query param auth test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | apiKey | id |
    | abc123 | 10 |
        """.trimIndent(), sourceSpecPath
        )

        val contractTests = contract.generateContractTestScenarios(emptyList())
        val result = executeTest(contractTests.single(), object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.queryParams).containsEntry("apiKey", "abc123")
                return HttpResponse.OK("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }

        })

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should generate stub with http post and non primitive request and response data types`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val petResponse = HttpStub(feature).use {
            val restTemplate = RestTemplate()
            restTemplate.postForObject(
                URI.create("http://localhost:9000/pets"),
                NewPet("scooby", "golden"),
                Pet::class.java
            )
        }

        assertThat(petResponse).isInstanceOf(Pet::class.java)
        assertThat(petResponse).isNotNull
    }

    @Test
    fun `should generate stub with http patch and non primitive request and response data types`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val petResponse = HttpStub(feature).use {
            val requestBody = RequestBody.create(
                parse("application/json"), ObjectMapper().writeValueAsString(Pet("scooby", "golden", 1, "retriever", 1))
            )
            val request =
                Request.Builder().url("http://localhost:9000/pets/1").addHeader("Content-Type", "application/json")
                    .patch(requestBody).build()
            val call = OkHttpClient().newCall(request)
            call.execute()
        }

        assertThat(petResponse.isSuccessful).isTrue
        assertThat(petResponse.code()).isEqualTo(200)
        assertThat(ObjectMapper().readValue(petResponse.body()?.string(), Pet::class.java)).isNotNull
    }

    @Test
    fun `should validate with cyclic reference in open api`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-post.yaml
        """.trimIndent(), sourceSpecPath
        )

        val result = testBackwardCompatibility(feature, feature)
        assertThat(result.success()).isTrue()
    }

    @Test
    fun `should validate and generate with indirect required non-nullable cyclic reference in open api`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/circular-reference-non-nullable.yaml
        """.trimIndent(), sourceSpecPath
        )

        val result = testBackwardCompatibility(feature, feature)
        assertThat(result.success()).isTrue()

        val resp = HttpStub(feature).use {
            val request =
                Request.Builder().url("http://localhost:9000/demo/circular-reference-non-nullable")
                    .addHeader("Content-Type", "application/json")
                    .get().build()
            val call = OkHttpClient().newCall(request)
            call.execute()
        }

        assertThat(resp.isSuccessful).isFalse
        assertThat(resp.code()).isEqualTo(400)
        val body = resp.body()?.string()
        assertThat(body).contains("Invalid pattern cycle")
    }

    @Test
    @RepeatedTest(10) // Try to exercise all outcomes of AnyPattern.generate() which randomly selects from its options
    fun `should validate and generate with indirect optional non-nullable cyclic reference in open api`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/circular-reference-optional-non-nullable.yaml
        """.trimIndent(), sourceSpecPath
        )

        val result = testBackwardCompatibility(feature, feature)
        assertThat(result.success()).isTrue()

        val resp = HttpStub(feature).use {
            val request =
                Request.Builder().url("http://localhost:9000/demo/circular-reference-optional-non-nullable")
                    .addHeader("Content-Type", "application/json")
                    .get().build()
            val call = OkHttpClient().newCall(request)
            call.execute()
        }

        val body = resp.body()?.string()
        assertThat(resp.isSuccessful).withFailMessage("Response unexpectedly failed. body=$body").isTrue
        assertThat(resp.code()).isEqualTo(200)
        val deserialized = ObjectMapper().readValue(body, OptionalCycleRoot::class.java)
        assertThat(deserialized).isNotNull
    }

    @Test
    @RepeatedTest(10) // Try to exercise all outcomes of AnyPattern.generate() which randomly selects from its options
    fun `should validate and generate with indirect nullable cyclic reference in open api`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/circular-reference-nullable.yaml
        """.trimIndent(), sourceSpecPath
        )

        val result = testBackwardCompatibility(feature, feature)
        assertThat(result.success()).isTrue()

        val resp = HttpStub(feature).use {
            val request =
                Request.Builder().url("http://localhost:9000/demo/circular-reference-nullable")
                    .addHeader("Content-Type", "application/json")
                    .get().build()
            val call = OkHttpClient().newCall(request)
            call.execute()
        }

        val body = resp.body()?.string()
        assertThat(resp.isSuccessful).withFailMessage("Response unexpectedly failed. body=$body").isTrue
        assertThat(resp.code()).isEqualTo(200)
        val deserialized = ObjectMapper().readValue(body, NullableCycleHolder::class.java)
        assertThat(deserialized).isNotNull
    }

    @Test
    @RepeatedTest(10) // Try to exercise all outcomes of AnyPattern.generate() which randomly selects from its options
    fun `should validate and generate with polymorphic cyclic reference in open api`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/circular-reference-polymorphic.yaml
        """.trimIndent(), sourceSpecPath
        )

        val result = testBackwardCompatibility(feature, feature)
        assertThat(result.success()).isTrue()

        val resp = HttpStub(feature).use {
            val request =
                Request.Builder().url("http://localhost:9000/demo/circular-reference-polymorphic")
                    .addHeader("Content-Type", "application/json")
                    .get().build()
            val call = OkHttpClient().newCall(request)
            call.execute()
        }

        val body = resp.body()?.string()
        assertThat(resp.isSuccessful).withFailMessage("Response unexpectedly failed. body=$body").isTrue
        assertThat(resp.code()).isEqualTo(200)
        val deserialized = ObjectMapper().readValue(body, MyBaseHolder::class.java)
        assertThat(deserialized).isNotNull
    }

    //TODO:
    @Ignore
    fun `should generate stub with cyclic reference in open api`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-post.yaml
        """.trimIndent(), sourceSpecPath
        )

        val petResponse = HttpStub(feature).use {
            val restTemplate = RestTemplate()
            restTemplate.postForObject(
                URI.create("http://localhost:9000/pets"),
                NewPet("scooby", "golden"),
                Pet::class.java
            )
        }

        assertThat(petResponse).isInstanceOf(CyclicPet::class.java)
        assertThat(petResponse).isNotNull
    }

    @Test
    fun `should parse nullable fields`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val petResponse = HttpStub(feature).use {
            val restTemplate = RestTemplate()
            restTemplate.postForObject(
                URI.create("http://localhost:9000/pets"),
                NewPet("scooby", null),
                Pet::class.java
            )
        }

        assertThat(petResponse).isInstanceOf(Pet::class.java)
        assertThat(petResponse).isNotNull
    }

    @Test
    fun `should generate stub with non primitive request which throws error on unexpected fields`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        HttpStub(feature).use {
            val restTemplate = RestTemplate()
            try {
                restTemplate.postForObject(
                    URI.create("http://localhost:9000/pets"),
                    NewPetWithUnexpectedFields("scooby", "golden", Integer(4)),
                    Pet::class.java
                )
                throw AssertionError("Should not allow unexpected fields")
            } catch (e: HttpClientErrorException) {
                assertThat(e.statusCode).isEqualTo(BAD_REQUEST)
            }
        }
    }

    @Test
    fun `should generate stub with non primitive request which allows optional fields`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val petResponse = HttpStub(feature).use {
            val restTemplate = RestTemplate()
            restTemplate.postForObject(
                URI.create("http://localhost:9000/pets"),
                NewPetWithMissingTag("scooby"),
                Pet::class.java
            )
        }

        assertThat(petResponse).isInstanceOf(Pet::class.java)
        assertThat(petResponse).isNotNull
    }

    @Test
    fun `should generate stub with non primitive request which throws error on missing required fields`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        HttpStub(feature).use {
            val restTemplate = RestTemplate()
            try {
                restTemplate.postForObject(
                    URI.create("http://localhost:9000/pets"),
                    NewPetWithMissingName("golden"),
                    Pet::class.java
                )
                throw AssertionError("Should not allow empty value on the name field which is required")
            } catch (e: HttpClientErrorException) {
                assertThat(e.statusCode).isEqualTo(BAD_REQUEST)
            }
        }
    }

    @Test
    fun `should create petstore tests`() {
        val flags = mutableMapOf<String, Int>().withDefault { 0 }

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
  
  Scenario: get by tag
    When POST /pets
    Then status 201
    Examples:
      | tag     | name |
      | testing | test |
      
  Scenario: zero return bad request
    When GET /pets/0
    Then status 400
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val flagKey = "${request.path} ${request.method} executed"
                    flags[flagKey] = flags.getValue(flagKey) + 1
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val pet = Pet("scooby", "golden", 1, "retriever", 2)
                    return when {
                        request.path!!.matches(Regex("""\/pets\/[0-9]+""")) -> when (request.method) {
                            "GET" -> {
                                when (request.path) {
                                    "/pets/0" -> HttpResponse(
                                        400,
                                        ObjectMapper().writeValueAsString(Error(1, "zero is not allowed")),
                                        headers
                                    )
                                    else -> HttpResponse(
                                        200,
                                        ObjectMapper().writeValueAsString(pet),
                                        headers
                                    )
                                }
                            }
                            "DELETE" -> HttpResponse(
                                204,
                                headers
                            )
                            "PATCH" -> {
                                HttpResponse(
                                    200,
                                    ObjectMapper().writeValueAsString(pet),
                                    headers
                                )
                            }
                            else -> HttpResponse(400, "", headers)
                        }
                        request.path == "/pets" -> {
                            when (request.method) {
                                "GET" -> {
                                    HttpResponse(
                                        200,
                                        ObjectMapper().writeValueAsString(listOf(pet)),
                                        object : HashMap<String, String>() {
                                            init {
                                                put("Content-Type", "application/json")
                                                put("X-RateLimit-Reset", "2021-05-31T17:32:28Z")
                                                put("X-Date-DataType", "2021-05-31")
                                                put("X-Boolean-DataType", "true")
                                                put("X-Number-DataType", "123123.123123")
                                            }
                                        }
                                    )
                                }
                                "POST" -> {
                                    assertThat(request.bodyString).containsAnyOf(
                                        """
                                        {
                                            "tag": "testing",
                                            "name": "test"
                                        }
                                    """.trimIndent(),
                                        """
                                        {
                                            "name": "test"
                                        }
                                    """.trimIndent()
                                    )
                                    HttpResponse(
                                        201,
                                        ObjectMapper().writeValueAsString(pet),
                                        headers
                                    )
                                }
                                else -> HttpResponse(400, "", headers)
                            }
                        }
                        request.path == "/petIds" -> {
                            when (request.method) {
                                "GET" -> {
                                    if (request.headers.containsKey("Authorization")) {
                                        HttpResponse(
                                            200,
                                            ObjectMapper().writeValueAsString(listOf(1)),
                                            headers
                                        )
                                    } else {
                                        HttpResponse(403, "UnAuthorized", headers)
                                    }
                                }
                                else -> HttpResponse(400, "", headers)
                            }
                        }
                        else -> HttpResponse(400, "", headers)
                    }
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["/pets POST executed"]).isEqualTo(1)
        assertThat(flags["/pets GET executed"]).isEqualTo(24)
        assertThat(flags["/petIds GET executed"]).isEqualTo(4)
        assertThat(flags["/pets/0 GET executed"]).isEqualTo(1)
        assertThat(flags.keys.filter { it.matches(Regex("""\/pets\/[0-9]+ GET executed""")) }.size).isEqualTo(2)
        assertThat(flags.keys.any { it.matches(Regex("""\/pets\/[0-9]+ DELETE executed""")) }).isNotNull
        assertThat(flags.keys.filter { it.matches(Regex("""\/pets\/[0-9]+ PATCH executed""")) }.size).isEqualTo(7)
        assertThat(flags.size).isEqualTo(13)
        assertTrue(results.success(), results.report())
    }

    @Test
    fun `should report errors when a value other than string enum is returned`() {
        val flags = mutableMapOf<String, Int>().withDefault { 0 }

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.copy(generativeTestingEnabled = false).executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val flagKey = "${request.path} ${request.method} executed"
                    flags[flagKey] = flags.getValue(flagKey) + 1
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val pet = Pet("scooby", "golden", 1, "malinois", 2)
                    return when {
                        request.path == "/pets" -> {
                            when (request.method) {
                                "POST" -> {
                                    HttpResponse(
                                        201,
                                        ObjectMapper().writeValueAsString(pet),
                                        headers
                                    )
                                }
                                else -> HttpResponse(400, "", headers)
                            }
                        }
                        else -> HttpResponse(400, "", headers)
                    }
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            },
            scenarioNames = listOf("create a pet. Response: pet response")
        )

        assertFalse(results.success())
        val reportText = """
            In scenario "create a pet. Response: pet response"
            API: POST /pets -> 201

              >> RESPONSE.BODY.breed
              
                 ${
            ContractAndResponseMismatch.mismatchMessage(
                """("labrador" or "retriever" or "null")""",
                "\"malinois\""
            )
        }
            """.trimIndent()
        assertThat(results.report()).contains(
            reportText
        )

        assertThat(countMatches(results.report(), reportText)).isEqualTo(3)
    }

    @Test
    fun `should report errors when a value other than numeric enum is returned`() {
        val flags = mutableMapOf<String, Int>().withDefault { 0 }

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val flagKey = "${request.path} ${request.method} executed"
                    flags[flagKey] = flags.getValue(flagKey) + 1
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val pet = Pet("scooby", "golden", 1, "retriever", 3)
                    return when {
                        request.path == "/pets" -> {
                            when (request.method) {
                                "POST" -> {
                                    HttpResponse(
                                        201,
                                        ObjectMapper().writeValueAsString(pet),
                                        headers
                                    )
                                }
                                else -> HttpResponse(400, "", headers)
                            }
                        }
                        else -> HttpResponse(400, "", headers)
                    }
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            },
            scenarioNames = listOf("create a pet. Response: pet response")
        )

        assertFalse(results.success())
        val reportText = """
            In scenario "create a pet. Response: pet response"
            API: POST /pets -> 201
            
              >> RESPONSE.BODY.rating
              
                 ${ContractAndResponseMismatch.mismatchMessage("(1 or 2)", "3 (number)")}
            """.trimIndent()
        assertThat(results.report()).contains(
            reportText
        )

        assertThat(countMatches(results.report(), reportText)).isEqualTo(3)
    }

    @Test
    fun `should report errors when a string is not as per restrictions`() {
        val flags = mutableMapOf<String, Int>().withDefault { 0 }

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/petstore-expanded.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val flagKey = "${request.path} ${request.method} executed"
                    flags[flagKey] = flags.getValue(flagKey) + 1
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    val pet = Pet("small", "golden", 1, "retriever", 2)
                    return when {
                        request.path == "/pets" -> {
                            when (request.method) {
                                "POST" -> {
                                    HttpResponse(
                                        201,
                                        ObjectMapper().writeValueAsString(pet),
                                        headers
                                    )
                                }
                                else -> HttpResponse(400, "", headers)
                            }
                        }
                        else -> HttpResponse(400, "", headers)
                    }
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            },
            scenarioNames = listOf("create a pet. Response: pet response")
        )

        assertFalse(results.success())
        val expectedReport = """
            In scenario "create a pet. Response: pet response"
            API: POST /pets -> 201

              >> RESPONSE.BODY.name
              
                 ${ContractAndResponseMismatch.mismatchMessage("string with minLength 6", "\"small\"")}
            """.trimIndent()
        assertThat(results.report()).contains(
            expectedReport
        )

        assertThat(countMatches(results.report(), expectedReport)).isEqualTo(3)
    }

    fun countMatches(string: String, pattern: String): Int {
        var index = 0
        var count = 0

        while (true) {
            index = string.indexOf(pattern, index)
            index += if (index != -1) {
                count++
                pattern.length
            } else {
                return count
            }
        }
    }

    @Test
    fun `should generate stub with json in form data`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/json-in-form-data.yaml
        """.trimIndent(), sourceSpecPath
        )

        val headers = HttpHeaders()
        headers.set("Content-Type", "application/x-www-form-urlencoded")

        val map: MultiValueMap<String, String> = LinkedMultiValueMap()
        map.add("payload", """{"text":"json inside form data"}""")
        map.add("nonJsonPayload", "test")

        val request: HttpEntity<MultiValueMap<String, String>> = HttpEntity<MultiValueMap<String, String>>(map, headers)

        val response = HttpStub(feature).use {
            val restTemplate = RestTemplate()
            restTemplate.postForEntity(
                URI.create("http://localhost:9000/services/jsonAndNonJsonPayload"),
                request,
                String::class.java
            )
        }

        assertThat(response).isNotNull
        assertThat(response.statusCodeValue).isEqualTo(200)
    }

    @Test
    fun `should generate tests with json in form data`() {
        val flags = mutableMapOf<String, Int>().withDefault { 0 }

        val feature = parseGherkinStringToFeature(
            """
Feature: Hello world

Background:
  Given openapi openapi/json-in-form-data.yaml
        """.trimIndent(), sourceSpecPath
        )

        val results = feature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val flagKey = "${request.path} ${request.method} executed"
                    flags[flagKey] = flags.getValue(flagKey) + 1
                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }
                    return when (request.path) {
                        "/services/jsonAndNonJsonPayload" -> {
                            if (request.method == "POST" &&
                                request.headers["Content-Type"] == "application/x-www-form-urlencoded" &&
                                readFormField(request, "payload")["text"] != null
                            ) HttpResponse(
                                200,
                                "",
                                headers
                            ) else return HttpResponse(400, "", headers)

                        }
                        "/services/nonJsonPayloadOnly" -> {
                            if (request.method == "POST" &&
                                request.headers["Content-Type"] == "application/x-www-form-urlencoded" &&
                                request.formFields["nonJsonPayload"] != null
                            ) HttpResponse(
                                200,
                                "",
                                headers
                            ) else return HttpResponse(400, "", headers)

                        }
                        else -> return HttpResponse(400, "", headers)
                    }
                }

                private fun readFormField(request: HttpRequest, fieldName: String) =
                    ObjectMapper().readValue(request.formFields[fieldName], Map::class.java)

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(flags["/services/jsonAndNonJsonPayload POST executed"]).isEqualTo(1)
        assertThat(flags["/services/nonJsonPayloadOnly POST executed"]).isEqualTo(1)
        assertThat(results.success()).isTrue
    }

    @Test
    fun `should not drop the query params declared in yaml when loading a test scenario from a wrapper spec`() {
        val openAPISpec = """
Feature: Hello world

Background:
  Given openapi openapi/helloWithQueryParams.yaml            

Scenario: zero should return not found
  When GET /hello
  Then status 200
        """.trimIndent()

        val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

        var executed = false

        val result = executeTest(feature.scenarios.first(), object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                executed = true
                return if (request.queryParams.keys.containsAll(listOf("name", "message"))) HttpResponse.OK
                else HttpResponse.ERROR_400
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }
        })

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(executed).isTrue
    }

    @Test
    fun `should not send query params that have been explicitly omitted in examples`() {
        val openAPISpec = """
Feature: Hello world

Background:
  Given openapi openapi/helloWithQueryParams.yaml            

Scenario: zero should return not found
  When GET /hello
  Then status 200
  Examples:
      | message | name |
      | hello   | Hari |
      | hello   | (omit) |
        """.trimIndent()

        val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

        val queryParameters: MutableList<Map<String, String>> = mutableListOf()

        val results = feature.copy(generativeTestingEnabled = true).executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    queryParameters.add(request.queryParams)
                    return HttpResponse.OK
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertThat(results.success()).isTrue
        assertThat(queryParameters.size).isEqualTo(4)
        assertThat(queryParameters.map { it.keys }).containsAll(
            listOf(
                setOf("message"),
                setOf("message", "name"),
                setOf("message", "name", "another_message"),
                setOf("message", "another_message"),
            )
        )
        assertThat(queryParameters.map { it.values.toList() }).containsAll(listOf(listOf("Hari", "hello"), listOf("hello")))
    }

    @Test
    fun `default response should be used to match an unexpected response status code and body in stub`() {
        val openAPISpec = """
            Feature: With default
            
            Background:
              Given openapi openapi/with_default.yaml
        """.trimIndent()

        val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

        val result = feature.matches(
            HttpRequest("GET", "/hello/10"),
            HttpResponse(500, body = parsedJSONObject("""{"data": "information"}"""))
        )

        assertThat(result).isTrue
    }

    @Test
    fun `default response should be used to match an unexpected response status code and body in a negative test`() {
        val openAPISpec = """
            Feature: With default
            
            Background:
              Given openapi openapi/post_with_default.yaml
        """.trimIndent()

        val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

        try {
            System.setProperty(Flags.negativeTestingFlag, "true")

            val results: Results = feature.copy(generativeTestingEnabled = true).executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val jsonBody = request.body as JSONObjectValue
                    if (jsonBody.jsonObject.get("id")?.toStringLiteral()?.toIntOrNull() != null)
                        return HttpResponse(200, body = StringValue("it worked"))

                    return HttpResponse(400, body = parsedJSONObject("""{"data": "information"}"""))
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            })

            println(results.report())

            assertThat(results.success()).isTrue
        } finally {
            System.clearProperty(Flags.negativeTestingFlag)
        }
    }

    @Test
    fun `should validate enum values in URL path params`() {
        val openAPISpec = """
Feature: Foo API

  Background:
    Given openapi openapi/enum_in_path.yaml

  Scenario Outline: Delete foo
    When GET /v1/foo/(data:string)
    Then status 200
    Examples:
      | data |
      | baz  |
        """.trimIndent()

        val flags = mutableListOf<String>()

        try {
            val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

            feature.executeTests(
                object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        flags.add("test executed")
                        return HttpResponse.OK
                    }

                    override fun setServerState(serverState: Map<String, Value>) {}
                }
            )

        } catch(e: Throwable) {

        }

        assertThat(flags).doesNotContain("test executed")
    }

    @Test
    fun `contract-invalid test should be allowed for 400 request payload`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: "3.0.3"
info:
  version: 1.0.0
  title: Swagger Petstore
  description: A sample API that uses a petstore as an example to demonstrate features in the OpenAPI 3.0 specification
  termsOfService: http://swagger.io/terms/
  contact:
    name: Swagger API Team
    email: apiteam@swagger.io
    url: http://swagger.io
  license:
    name: Apache 2.0
    url: https://www.apache.org/licenses/LICENSE-2.0.html
servers:
  - url: http://petstore.swagger.io/api
paths:
  /pets:
    post:
      summary: create a pet
      description: Creates a new pet in the store. Duplicates are allowed
      operationId: addPet
      requestBody:
        description: Pet to add to the store
        required: true
        content:
          application/json:
            schema:
              ${'$'}ref: '#/components/schemas/NewPet'
            examples:
              SUCCESS:
                value:
                  name: 'Archie'
              INVALID:
                value:
                  name: 10
      responses:
        '200':
          description: new pet record
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Pet'
              examples:
                SUCCESS:
                  value:
                    id: 10
                    name: Archie
        '400':
          description: invalid request
          content:
            application/json:
              examples:
                INVALID:
                  value:
                    message: Name must be a strings
              schema:
                type: object
                properties:
                  message:
                    type: string
components:
  schemas:
    Pet:
      type: object
      required:
        - id
        - name
      properties:
        name:
          type: string
        id:
          type: integer
    NewPet:
      type: object
      required:
        - name
      properties:
        name:
          type: string
""".trimIndent(), "").toFeature()

        var contractInvalidValueReceived = false

        try {
            contract.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val jsonBody = request.body as JSONObjectValue

                    if(jsonBody.jsonObject["name"] is NumberValue)
                        contractInvalidValueReceived = true

                    return HttpResponse(400, body = parsedJSONObject("""{"message": "invalid request"}"""))
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            })

            assertThat(contractInvalidValueReceived).isTrue
        } finally {
            System.clearProperty(Flags.negativeTestingFlag)
        }
    }

    @Test
    fun `contract-invalid test should be allowed for 400 query parameter`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: "3.0.3"
info:
  version: 1.0.0
  title: Swagger Petstore
  description: A sample API that uses a petstore as an example to demonstrate features in the OpenAPI 3.0 specification
  termsOfService: http://swagger.io/terms/
  contact:
    name: Swagger API Team
    email: apiteam@swagger.io
    url: http://swagger.io
  license:
    name: Apache 2.0
    url: https://www.apache.org/licenses/LICENSE-2.0.html
servers:
  - url: http://petstore.swagger.io/api
paths:
  /pets:
    post:
      summary: create a pet
      description: Creates a new pet in the store. Duplicates are allowed
      operationId: addPet
      parameters:
        - in: header
          name: data
          schema:
            type: integer
          examples:
            INVALID:
              value: hello
            SUCCESS:
              value: 10
      requestBody:
        description: Pet to add to the store
        required: true
        content:
          application/json:
            schema:
              ${'$'}ref: '#/components/schemas/NewPet'
            examples:
              SUCCESS:
                value:
                  name: 'Archie'
              INVALID:
                value:
                  name: 10
      responses:
        '200':
          description: new pet record
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Pet'
              examples:
                SUCCESS:
                  value:
                    id: 10
                    name: Archie
        '400':
          description: invalid request
          content:
            application/json:
              examples:
                INVALID:
                  value:
                    message: Name must be a strings
              schema:
                type: object
                properties:
                  message:
                    type: string
components:
  schemas:
    Pet:
      type: object
      required:
        - id
        - name
      properties:
        name:
          type: string
        id:
          type: integer
    NewPet:
      type: object
      required:
        - name
      properties:
        name:
          type: string
""".trimIndent(), "").toFeature()

        var contractInvalidValueReceived = false

        try {
            contract.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val dataHeaderValue: String? = request.headers["data"]

                    if(dataHeaderValue == "hello")
                        contractInvalidValueReceived = true

                    return HttpResponse(400, body = parsedJSONObject("""{"message": "invalid request"}"""))
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            })

            assertThat(contractInvalidValueReceived).isTrue
        } finally {
            System.clearProperty(Flags.negativeTestingFlag)
        }
    }

    @Test
    fun `contract-invalid test should be allowed for 400 request header`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: "3.0.3"
info:
  version: 1.0.0
  title: Swagger Petstore
  description: A sample API that uses a petstore as an example to demonstrate features in the OpenAPI 3.0 specification
  termsOfService: http://swagger.io/terms/
  contact:
    name: Swagger API Team
    email: apiteam@swagger.io
    url: http://swagger.io
  license:
    name: Apache 2.0
    url: https://www.apache.org/licenses/LICENSE-2.0.html
servers:
  - url: http://petstore.swagger.io/api
paths:
  /pets:
    get:
      summary: query for a pet
      description: Queries info on a pet
      parameters:
        - in: query
          name: data
          schema:
            type: integer
          examples:
            INVALID:
              value: hello
            SUCCESS:
              value: 10
      responses:
        '200':
          description: new pet record
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Pet'
              examples:
                SUCCESS:
                  value:
                    id: 10
                    name: Archie
        '400':
          description: invalid request
          content:
            application/json:
              examples:
                INVALID:
                  value:
                    message: Name must be a strings
              schema:
                type: object
                properties:
                  message:
                    type: string
components:
  schemas:
    Pet:
      type: object
      required:
        - id
        - name
      properties:
        name:
          type: string
        id:
          type: integer
    NewPet:
      type: object
      required:
        - name
      properties:
        name:
          type: string
""".trimIndent(), "").toFeature()

        var contractInvalidValueReceived = false

        try {
            contract.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val dataHeaderValue: String? = request.queryParams["data"]

                    if(dataHeaderValue == "hello")
                        contractInvalidValueReceived = true

                    return HttpResponse(400, body = parsedJSONObject("""{"message": "invalid request"}"""))
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            })

            assertThat(contractInvalidValueReceived).isTrue
        } finally {
            System.clearProperty(Flags.negativeTestingFlag)
        }
    }

    @Test
    fun `a test marked WIP should setup the scenario to ignore failure`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: "3.0.3"
info:
  version: 1.0.0
  title: Swagger Petstore
  description: A sample API that uses a petstore as an example to demonstrate features in the OpenAPI 3.0 specification
  termsOfService: http://swagger.io/terms/
  contact:
    name: Swagger API Team
    email: apiteam@swagger.io
    url: http://swagger.io
  license:
    name: Apache 2.0
    url: https://www.apache.org/licenses/LICENSE-2.0.html
servers:
  - url: http://petstore.swagger.io/api
paths:
  /pets:
    post:
      summary: create a pet
      description: Creates a new pet in the store. Duplicates are allowed
      operationId: addPet
      requestBody:
        description: Pet to add to the store
        required: true
        content:
          application/json:
            schema:
              ${'$'}ref: '#/components/schemas/NewPet'
            examples:
              "[WIP] SUCCESS":
                value:
                  name: 'Archie'
      responses:
        '200':
          description: new pet record
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Pet'
              examples:
                "[WIP] SUCCESS":
                  value:
                    id: 10
                    name: Archie
components:
  schemas:
    Pet:
      type: object
      required:
        - id
        - name
      properties:
        name:
          type: string
        id:
          type: integer
    NewPet:
      type: object
      required:
        - name
      properties:
        name:
          type: string
""".trimIndent(), "").toFeature()

        assertThat(contract.generateContractTestScenarios(emptyList()).single().ignoreFailure).isTrue
    }

    @Test
    fun `400 response in the contract should be used to match a 400 status response in a negative test even when a default response has been declared`() {
        val openAPISpec = """
            Feature: With default
            
            Background:
              Given openapi openapi/post_with_default_and_400.yaml
        """.trimIndent()

        val feature = parseGherkinStringToFeature(openAPISpec, sourceSpecPath)

        try {
            System.setProperty(Flags.negativeTestingFlag, "true")

            val results: Results = feature.copy(generativeTestingEnabled = true).executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val jsonBody = request.body as JSONObjectValue
                    if (jsonBody.jsonObject.get("id")?.toStringLiteral()?.toIntOrNull() != null)
                        return HttpResponse(200, body = StringValue("it worked"))

                    return HttpResponse(400, body = parsedJSONObject("""{"error_in_400": "message"}"""))
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            })

            println(results.report())

            assertThat(results.success()).isTrue
        } finally {
            System.clearProperty(Flags.negativeTestingFlag)
        }
    }

    @Test
    fun `should run test from wrapper gherkin with unconstrained type in url`() {
        val contract = parseGherkinStringToFeature("""
            Feature: Test wrapper of constraints
              Background:
                Given openapi core/src/test/resources/openapi/hello_with_constraints.yaml
                
              Scenario: Test
                When GET /hello/(id:string)
                Then status 200
                
                Examples:
                | id         |
                | 1234567890 |
                | 0987654321 |
        """.trimIndent())

        var testCount = 0

        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val idValue = request.path!!.split("/").last()
                assertThat(idValue).hasSizeGreaterThan(9)
                assertThat(idValue).hasSizeLessThan(21)

                testCount += 1

                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(testCount).isEqualTo(2)
    }

    @Test
    fun `should fail to run a test from wrapper gherkin with pattern url matching concrete specification value`() {
        assertThatThrownBy {
            parseGherkinStringToFeature(
                """
            Feature: Test wrapper of constraints
              Background:
                Given openapi core/src/test/resources/openapi/hello_with_constraints.yaml
                
              Scenario: Test
                When GET /(val:string)/(id:string)
                Then status 200
        """.trimIndent()
            )
        }.satisfies(Consumer {
            assertThat(it).isInstanceOf(ContractException::class.java)
            assertThat(exceptionCauseMessage(it)).contains("not as per")
        })
    }

    fun `should preserve trailing slash`() {
val contract = OpenApiSpecification.fromYAML("""
    openapi: "3.0.3"
    info:
      version: 1.0.0
      title: Swagger Petstore
      description: A sample API that uses a petstore as an example to demonstrate features in the OpenAPI 3.0 specification
      termsOfService: http://swagger.io/terms/
      contact:
        name: Swagger API Team
        email: apiteam@swagger.io
        url: http://swagger.io
      license:
        name: Apache 2.0
        url: https://www.apache.org/licenses/LICENSE-2.0.html
    servers:
      - url: http://petstore.swagger.io/api
    paths:
      /pets/:
        post:
          summary: create a pet
          description: Creates a new pet in the store. Duplicates are allowed
          operationId: addPet
          requestBody:
            description: Pet to add to the store
            required: true
            content:
              application/json:
                schema:
                  ${'$'}ref: '#/components/schemas/NewPet'
                examples:
                  SUCCESS:
                    value:
                      name: 'Archie'
          responses:
            '200':
              description: new pet record
              content:
                application/json:
                  schema:
                    ${'$'}ref: '#/components/schemas/Pet'
                  examples:
                    SUCCESS:
                      value:
                        id: 10
                        name: Archie
    components:
      schemas:
        Pet:
          type: object
          required:
            - id
            - name
          properties:
            name:
              type: string
            id:
              type: integer
        NewPet:
          type: object
          required:
            - name
          properties:
            name:
              type: string
""".trimIndent(), "").toFeature()

        val paths = mutableListOf<String>()

        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                paths.add(request.path!!)
                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }
        })

        assertThat(paths).allSatisfy {
            assertThat(it).endsWith("/")
        }
    }

    @Test
    fun `should error if example field not used`() {
        assertThatThrownBy {
            parseGherkinStringToFeature(
                """
            Feature: Test wrapper of constraints
              Background:
                Given openapi core/src/test/resources/openapi/hello_with_constraints.yaml
                
              Scenario: Test
                When GET /hello/(id:string)
                Then status 200
                Examples:
                | unused1         |
                | 1234567890 |
                | 0987654321 |
        """.trimIndent()
            )
        }
            .hasRootCauseInstanceOf(ContractException::class.java)
            .hasMessageContaining("unused1")
    }

}

data class CycleRoot(
    @JsonProperty("intermediate-node") val intermediateNode: CycleIntermediateNode,
)

data class CycleIntermediateNode(
    @JsonProperty("indirect-cycle") val indirectCycle: CycleRoot,
)

data class OptionalCycleRoot(
    @JsonProperty("intermediate-node") val intermediateNode: OptionalCycleIntermediateNode,
)

data class OptionalCycleIntermediateNode(
    @JsonProperty("indirect-cycle") val indirectCycle: OptionalCycleRoot?,
)

data class NullableCycleHolder(
    @JsonProperty("contents") val contents: NullableCycleRoot?,
)
data class NullableCycleRoot(
    @JsonProperty("intermediate-node") val intermediateNode: NullableCycleIntermediateNode,
)

data class NullableCycleIntermediateNode(
    @JsonProperty("indirect-cycle") val indirectCycle: NullableCycleRoot?,
)

data class MyBaseHolder(@JsonProperty("myBase") val myBase: MyBase)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MySub1::class),
    JsonSubTypes.Type(value = MySub2::class),
)
interface MyBase {}
data class MySub1(@JsonProperty("aMyBase") val aMyBase: MyBase?): MyBase
data class MySub2(@JsonProperty("myVal") val myVal: String): MyBase

data class Pet(
    @JsonProperty("name") val name: String,
    @JsonProperty("tag") val tag: String,
    @JsonProperty("id") val id: Int,
    @JsonProperty("breed") val breed: String?,
    @JsonProperty("rating") val rating: Int
)

data class CyclicPet(
    @JsonProperty("name") val name: String,
    @JsonProperty("tag") val tag: String,
    @JsonProperty("id") val id: Int,
    @JsonProperty("parent") val parent: CyclicPet
)

data class NewPet(
    @JsonProperty("name") val name: String,
    @JsonProperty("tag") val tag: String?,
)

data class NewPetWithUnexpectedFields(
    @JsonProperty("name") val name: String,
    @JsonProperty("tag") val tag: String,
    @JsonProperty("age") val age: Integer,
)

data class NewPetWithMissingTag(
    @JsonProperty("name") val name: String,
)

data class NewPetWithMissingName(
    @JsonProperty("tag") val tag: String,
)

data class Error(
    @JsonProperty("code") val code: Int,
    @JsonProperty("message") val message: String
)