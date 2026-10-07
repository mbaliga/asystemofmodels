package xyz.mdhv.asom.lab.conformance

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JUnit mode of the runner (LAB_SPEC 3.3): one dynamic test per vector, grouped by family. The family lines are
 * printed once, before the tests, from the same results the tests assert. Evidence label: LAB, oracle: self.
 */
class ConformanceSuiteTest {

    companion object {
        val report: SuiteReport by lazy {
            val r = Suite.run()
            println("== conformance suite ${File(Repo.conformance, "VERSION").readUtf8().trim()} (LAB; oracle: self; NOT DEVICE EVIDENCE)")
            print(r.render())
            val out = File(Repo.root, "lab/build")
            out.mkdirs()
            File(out, "conformance-report.txt").writeUtf8(r.render())
            r
        }
    }

    @TestFactory
    fun vectors(): List<DynamicNode> =
        report.families.filter { it.implemented }.map { f ->
            val tests = f.results.map { (v, outcome) ->
                dynamicTest("${v.id} ${v.description.take(70)}") {
                    when (outcome) {
                        is Outcome.Pass -> Unit
                        is Outcome.Skipped -> assumeTrue(false, "proposed-skipped: ${outcome.why}")
                        is Outcome.Fail ->
                            if (v.proposed) assumeTrue(false, "proposed-lane finding (non-blocking): ${outcome.why}")
                            else fail("${v.id}: ${outcome.why}")
                    }
                }
            } + dynamicTest("${f.family} is not vacuous") {
                assertEquals(emptyList(), f.vacuity())
            }
            dynamicContainer("family ${f.family}", tests)
        }

    @Test
    fun envelopesAreValid() {
        assertEquals(emptyList(), report.loaded.problems)
    }

    @Test
    fun indexMatchesTheCheckout() {
        assertEquals(emptyList(), IndexCheck.problems())
    }

    @Test
    fun everyL01FamilyRan() {
        for (fam in L01_FAMILIES) {
            val f = report.families.single { it.family == fam }
            assertTrue(f.implemented && f.normativeCount > 0, "L0.1 family $fam has no normative vectors")
        }
    }

    @Test
    fun unbuiltFamiliesAreNeverReportedAsPass() {
        val built = report.families.filter { !it.implemented }
        assertTrue(built.isNotEmpty())
        built.forEach {
            assertEquals("family ${it.family}: not-implemented", it.line())
            assertEquals(0, it.pass)
        }
    }

    @Test
    fun everyVectorCarriesTheSelfOracleTag() {
        val notSelf = report.loaded.all.filter { it.oracle != "self" }
        assertEquals(emptyList(), notSelf.map { it.id }, "L0.1 vectors stay oracle: self until an independent implementation agrees (LAB_SPEC 4.10)")
    }
}
