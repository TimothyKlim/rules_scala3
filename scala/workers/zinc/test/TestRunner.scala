package rules_scala
package workers.zinc.test

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Collections
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import org.scalatools.testing.Framework
import sbt.internal.inc.binary.converters.ProtobufReaders
import sbt.internal.inc.Schema
import scopt.OParser
import xsbti.compile.analysis.ReadMapper

import common.sbt_testing.*

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
      .action((arg, c) => c.copy(subprocessArg = c.subprocessArg :+ arg)),
  )

  def apply(args: collection.Seq[String]): Option[TestRunnerArguments] =
    OParser.parse(parser, args, TestRunnerArguments())

object TestRunner:
  import net.sourceforge.argparse4j.ArgumentParsers
  import net.sourceforge.argparse4j.impl.Arguments

  private val testArgParser =
    val parser = ArgumentParsers.newFor("test").addHelp(true).build()
    parser
      .addArgument("--apis")
      .help("APIs file")
      .metavar("class")
      .`type`(Arguments.fileType.verifyCanRead().verifyExists())
      .required(true)
    parser
      .addArgument("--subprocess_exec")
      .help("Executable for SubprocessTestRunner")
      .`type`(Arguments.fileType)
    parser
      .addArgument("--isolation")
      .choices("classloader", "none", "process")
      .help("Test isolation")
      .setDefault("none")
    parser
      .addArgument("--frameworks")
      .help("Class names of sbt.testing.Framework implementations")
      .metavar("class")
      .nargs("*")
      .setDefault(Collections.emptyList)
    parser
      .addArgument("--shared_classpath")
      .help("Classpath to share between tests")
      .metavar("path")
      .nargs("*")
      .`type`(Arguments.fileType)
      .setDefault(Collections.emptyList)
    parser
      .addArgument("classpath")
      .help("Testing classpath")
      .metavar("path")
      .nargs("*")
      .`type`(Arguments.fileType.verifyCanRead().verifyExists())
      .setDefault(Collections.emptyList)
    parser

  def main(args: Array[String]): Unit =
    val runArgs = TestRunnerArguments(args).getOrElse(throw IllegalArgumentException(s"args is invalid: ${args.mkString(" ")}"))

    sys.env.get("TEST_SHARD_STATUS_FILE").map { path =>
      val file = Paths.get(path)
      try Files.createFile(file)
      catch
        case _: FileAlreadyExistsException =>
          Files.setLastModifiedTime(file, FileTime.from(Instant.now))
    }

    val runPath = Paths.get(sys.props("bazel.runPath"))

    val testArgFile = Paths.get(sys.props("scalaAnnex.test.args"))
    val testNamespace = testArgParser.parseArgsOrFail(Files.readAllLines(testArgFile).asScala.toArray)

    val logger = AnnexTestingLogger(color = runArgs.color, runArgs.verbosity)

    val classpath = testNamespace
      .getList[File]("classpath")
      .asScala
      .map(file => runPath.resolve(file.toPath))
      .to(Seq)
    val sharedClasspath = testNamespace
      .getList[File]("shared_classpath")
      .asScala
      .map(file => runPath.resolve(file.toPath))

    val sharedUrls = classpath.filter(sharedClasspath.toSet).map(_.toUri.toURL)

    val classLoader = ClassLoaders.sbtTestClassLoader(classpath.map(_.toUri.toURL))
    val sharedClassLoader = ClassLoaders.sbtTestClassLoader(classpath.filter(sharedClasspath.toSet).map(_.toUri.toURL))

    val apisFile = runPath.resolve(testNamespace.get[File]("apis").toPath)
    val apisStream = Files.newInputStream(apisFile)
    val apis =
      try
        val raw =
          try Schema.APIs.parseFrom(GZIPInputStream(apisStream))
          finally apisStream.close()
        ProtobufReaders(ReadMapper.getEmptyMapper, Schema.Version.V1_1).fromApis(shouldStoreApis = true)(raw)
      catch case NonFatal(e) => throw Exception(s"Failed to load APIs from $apisFile", e)

    val loader = TestFrameworkLoader(classLoader, logger)
    val frameworks = testNamespace.getList[String]("frameworks").asScala.flatMap(loader.load)

    val testClass = sys.env
      .get("TESTBRIDGE_TEST_ONLY")
      .map(text => Pattern.compile(if (text.contains("#")) raw"${text.replaceAll("#.*", "")}" else text))
    val testScopeAndName = sys.env.get("TESTBRIDGE_TEST_ONLY").map {
      case text if text.contains("#") => text.replaceAll(".*#", "").replaceAll("\\$", "").replace("\\Q", "").replace("\\E", "")
      case _ => ""
    }

    var count = 0
    val passed = frameworks.forall { framework =>
      val tests = TestDiscovery(framework)(apis.internal.values.toSet).sortBy(_.name)
      val filter = for {
        index <- sys.env.get("TEST_SHARD_INDEX").map(_.toInt)
        total <- sys.env.get("TEST_TOTAL_SHARDS").map(_.toInt)
      } yield (test: TestDefinition, i: Int) => i % total == index
      val filteredTests = tests.filter { test =>
        testClass.forall(_.matcher(test.name).matches) && {
          count += 1
          filter.fold(true)(_(test, count))
        }
      }

      filteredTests.isEmpty || {
        val runner = testNamespace.getString("isolation") match
          case "classloader" =>
            val urls = classpath.filterNot(sharedClasspath.toSet).map(_.toUri.toURL).toArray
            def classLoaderProvider() = URLClassLoader(urls, sharedClassLoader)
            ClassLoaderTestRunner(framework, classLoaderProvider, logger)
          case "process" =>
            val executable = runPath.resolve(testNamespace.get[File]("subprocess_exec").toPath)
            ProcessTestRunner(framework, classpath, ProcessCommand(executable.toString, runArgs.subprocessArg), logger)
          case "none" => BasicTestRunner(framework, classLoader, logger)
        try runner.execute(filteredTests, testScopeAndName.getOrElse(""), runArgs.frameworkArgs)
        catch case e: Throwable =>
          e.printStackTrace()
          false
      }
    }

    sys.exit(if (passed) 0 else 1)
