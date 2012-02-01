package com.withoutincident.sbt {

import org.specs._
import org.specs.runner.JUnit4
import org.specs.runner.ConsoleRunner
import org.specs.matcher._
import org.specs.specification._

class GrowlTestListenerSpecsAsTest extends JUnit4(GrowlTestListenerTestSpecs)
object GrowlTestListenerTestSpecsRunner extends ConsoleRunner(GrowlTestListenerTestSpecs)

object GrowlTestListenerTestSpecs extends Specification {

  "GrowlTestListener" should {
    "something" in {
    }
  }
}

}
