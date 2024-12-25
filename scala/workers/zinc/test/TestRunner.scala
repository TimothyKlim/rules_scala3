package rules_scala
package workers.zinc.test

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{FileAlreadyExistsException, Files, Path, Paths}
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

import scala.language.unsafeNulls
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import sbt.internal.inc.Schema
import sbt.internal.inc.text.TextAnalysisFormat
import sbt.internal.inc.{APIs, Analysis, CompanionsStore}
import xsbti.compile.analysis.{ReadMapper, ReadWriteMappers}
import scopt.OParser
import xsbti.compile.analysis.ReadMapper
import java.io.{BufferedReader, InputStreamReader}

import common.sbt_testing.*
import workers.common.Bazel

enum Isolation:
  case ClassLoader, Process, None

  val value: String = productPrefix.toLowerCase
object Isolation:
  def from(value: String): Isolation = valuesMap(value.toLowerCase)

  given CanEqual[Isolation, Isolation] = CanEqual.derived

  private val valuesMap = values.map(v => v.value -> v).toMap
  given scopt.Read[Isolation] = scopt.Read.reads(from)

final case class TestRunnerArguments(
    color: Boolean = true,
    verbosity: Verbosity = Verbosity.MEDIUM,
    frameworkArgs: Seq[String] = Seq.empty,
    subprocessArg: Vector[String] = Vector.empty
)
object TestRunnerArguments:
  private val builder = OParser.builder[TestRunnerArguments]
  import builder.*

  private val parser = OParser.sequence(
    opt[Boolean]("color").action((f, c) => c.copy(color = f)),
    opt[String]("verbosity").action((v, c) => c.copy(verbosity = Verbosity.valueOf(v))),
    opt[String]("framework_args")
      .text("Additional arguments for testing framework")
      .action((args, c) => c.copy(frameworkArgs = args.split("\\s+").toSeq)),
    opt[String]("subprocess_arg")
      .text("Argument for tests run in new JVM process")
      .action((arg, c) => c.copy(subprocessArg = c.subprocessArg :+ arg))
  )

  def apply(args: collection.Seq[String]): Option[TestRunnerArguments] =
    OParser.parse(parser, args, TestRunnerArguments())

final case class TestWorkArguments(
    parallel: Boolean = false,
    parallelN: Option[Int] = None,
    apis: Path = Paths.get("."),
    subprocessExec: Path = Paths.get("."),
    isolation: Isolation = Isolation.None,
    sharedClasspath: Vector[Path] = Vector.empty,
    frameworks: Vector[String] = Vector.empty,
    classpath: Vector[Path] = Vector.empty
)
object TestWorkArguments:
  private val builder = OParser.builder[TestWorkArguments]
  import builder.*

  private val parser = OParser.sequence(
    opt[Boolean]("parallel").optional().action((v, c) => c.copy(parallel = v)),
    opt[Int]("parallel-n").optional().action((v, c) => c.copy(parallelN = Some(Math.max(1, v)))),
    opt[File]("apis").required().action((f, c) => c.copy(apis = f.toPath)).text("APIs file"),
    opt[File]("subprocess_exec").optional().action((f, c) => c.copy(subprocessExec = f.toPath)).text("Executable for SubprocessTestRunner"),
    opt[Isolation]("isolation").optional().action((iso, c) => c.copy(isolation = iso)).text("Test isolation"),
    opt[File]("shared_classpath")
      .unbounded()
      .optional()
      .action((f, c) => c.copy(sharedClasspath = c.sharedClasspath :+ f.toPath()))
      .text("Classpath to share between tests"),
    opt[String]("framework")
      .unbounded()
      .optional()
      .valueName("args")
      .action((arg, c) => c.copy(frameworks = c.frameworks :+ arg))
      .text("Class names of sbt-testing. Framework implementations"),
    arg[File]("<path>...")
      .unbounded()
      .optional()
      .action((f, c) => c.copy(classpath = c.classpath :+ f.toPath()))
      .text("Testing classpath")
  )

  def apply(args: collection.Seq[String]): Option[TestWorkArguments] =
    OParser.parse(parser, args, TestWorkArguments())

object TestRunner:
  def main(args: Array[String]): Unit =
    val runArgs = TestRunnerArguments(Bazel.parseParams(args)).getOrElse(throw IllegalArgumentException(s"args is invalid: ${args.mkString(" ")}"))

    sys.env.get("TEST_SHARD_STATUS_FILE").map { path =>
      val file = Paths.get(path)
      try Files.createFile(file)
      catch
        case _: FileAlreadyExistsException =>
          Files.setLastModifiedTime(file, FileTime.from(Instant.now))
    }

    val runPath = Paths.get(sys.props("bazel.runPath"))

    val testArgFile = Paths.get(sys.props("scalaAnnex.test.args"))
    val workerArgs = Bazel.parseParams(Files.readAllLines(testArgFile).asScala)
    val workArgs = TestWorkArguments(workerArgs).getOrElse(throw IllegalArgumentException(s"work args is invalid: ${workerArgs.mkString(" ")}"))

    val logger = AnnexTestingLogger(color = runArgs.color, runArgs.verbosity)

    val classpath = workArgs.classpath.map(runPath.resolve(_))
    val sharedClasspath = workArgs.sharedClasspath.map(runPath.resolve(_))

    val sharedUrls = classpath.filter(sharedClasspath.toSet).map(_.toUri.toURL)

    val classLoader = ClassLoaders.sbtTestClassLoader(classpath.map(_.toUri.toURL))
    val sharedClassLoader = ClassLoaders.sbtTestClassLoader(classpath.filter(sharedClasspath.toSet).map(_.toUri.toURL))

    val apisFile = runPath.resolve(workArgs.apis)
    val apisStream = Files.newInputStream(apisFile)
    val apis =
      try
        val bufferedReader = new BufferedReader(new InputStreamReader(apisStream))
        val analysisFormat = new TextAnalysisFormat(ReadWriteMappers.getEmptyMappers)
        val (compileAnalysis, _) = analysisFormat.read(bufferedReader, null)
        val analysis = compileAnalysis match {
          case a: Analysis => a
          case _ => throw new Exception("Failed to cast CompileAnalysis to Analysis")
        }
        analysis.apis
      catch {
        case NonFatal(e) => throw Exception(s"Failed to load APIs from $apisFile", e)
      }
      finally apisStream.close()

    val loader = TestFrameworkLoader(classLoader)
    val frameworks = workArgs.frameworks.flatMap(loader.load)

    val testClass = sys.env
      .get("TESTBRIDGE_TEST_ONLY")
      .map(text => Pattern.compile(if text.contains("#") then raw"${text.replaceAll("#.*", "")}" else text))
    val testScopeAndName = sys.env.get("TESTBRIDGE_TEST_ONLY").map {
      case text if text.contains("#") => text.replaceAll(".*#", "").replaceAll("\\$", "").replace("\\Q", "").replace("\\E", "")
      case _                          => ""
    }

    var count = 0
    val passed = frameworks.forall { framework =>
      val tests = TestDiscovery(framework)(apis.internal.values.toSet).sortBy(_.name)
      val filter = for
        index <- sys.env.get("TEST_SHARD_INDEX").map(_.toInt)
        total <- sys.env.get("TEST_TOTAL_SHARDS").map(_.toInt)
      yield (test: TestDefinition, i: Int) => i % total == index
      val filteredTests = tests.filter { test =>
        testClass.forall(_.matcher(test.name).matches) && {
          count += 1
          filter.fold(true)(_(test, count))
        }
      }

      filteredTests.isEmpty || {
        val runner = workArgs.isolation match
          case Isolation.ClassLoader =>
            val urls = classpath.filterNot(sharedClasspath.toSet).map(_.toUri.toURL).toArray
            def classLoaderProvider() = URLClassLoader(urls, sharedClassLoader)
            ClassLoaderTestRunner(framework, classLoaderProvider, parallel = workArgs.parallel, parallelN = workArgs.parallelN, logger)
          case Isolation.Process =>
            val executable = runPath.resolve(workArgs.subprocessExec)
            ProcessTestRunner(framework, classpath, ProcessCommand(executable.toString, runArgs.subprocessArg), logger)
          case Isolation.None =>
            BasicTestRunner(framework, classLoader, parallel = workArgs.parallel, parallelN = workArgs.parallelN, logger)

        try runner.execute(filteredTests, testScopeAndName.getOrElse(""), runArgs.frameworkArgs)
        catch
          case e: Throwable =>
            e.printStackTrace()
            false
      }
    }

    sys.exit(if passed then 0 else 1)
