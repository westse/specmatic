package application.test

import `in`.specmatic.core.log.logger
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import kotlin.system.exitProcess

fun getContractExecutionPrinter(): ContractExecutionPrinter {
    if(System.getenv("SPECMATIC_COLOR") != "1")
        return MonochromePrinter()

    return if(System.console() == null) MonochromePrinter() else ColorPrinter()
}

class ContractExecutionListener : TestExecutionListener {
    private var success: Int = 0
    private var failure: Int = 0
    private var aborted: Int = 0

    private val failedLog: MutableList<String> = mutableListOf()

    private var couldNotStart = false;

    private val colorPrinter: ContractExecutionPrinter = getContractExecutionPrinter()

    override fun executionSkipped(testIdentifier: TestIdentifier?, reason: String?) {
        super.executionSkipped(testIdentifier, reason)
    }

    override fun executionFinished(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?) {
        if (listOf("SpecmaticJUnitSupport", "contractAsTest()", "JUnit Jupiter").any {
                    testIdentifier!!.displayName.contains(it)
                }) {
                    if(testExecutionResult?.status != TestExecutionResult.Status.SUCCESSFUL)
                        couldNotStart = true

                    return
        }

        colorPrinter.printTestSummary(testIdentifier, testExecutionResult)

        when(testExecutionResult?.status) {
            TestExecutionResult.Status.SUCCESSFUL ->  {
                success++
                println()
            }
            TestExecutionResult.Status.ABORTED -> {
                aborted++
                printAndLogFailure(testExecutionResult, testIdentifier)
            }
            TestExecutionResult.Status.FAILED -> {
                failure++
                printAndLogFailure(testExecutionResult, testIdentifier)
            }
            else -> {
                logger.debug("A test called \"${testIdentifier?.displayName}\" ran but the test execution result was null. Please inform the Specmatic developer.")
            }
        }
    }

    private fun printAndLogFailure(
        testExecutionResult: TestExecutionResult,
        testIdentifier: TestIdentifier?
    ) {
        val message = testExecutionResult.throwable?.get()?.message?.replace("\n", "\n\t")?.trimIndent()
            ?: ""

        val reason = "Reason: $message"
        println("$reason\n\n")

        val log = """"${testIdentifier?.displayName} ${testExecutionResult.status}"
    ${reason.prependIndent("  ")}"""

        failedLog.add(log)
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan?) {
        org.fusesource.jansi.AnsiConsole.systemInstall()

        colorPrinter.printFinalSummary(TestSummary(success, aborted, failure))

        if (failedLog.isNotEmpty()) {
            println()
            colorPrinter.printFailureTitle("Unsuccessful scenarios:")
            println(failedLog.joinToString(System.lineSeparator()) { it.prependIndent("  ") })
        }
    }

    fun exitProcess() {
        val exitStatus = when (failure != 0 || couldNotStart) {
            true -> 1
            false -> 0
        }

        exitProcess(exitStatus)
    }
}