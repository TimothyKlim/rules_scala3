package rules_scala
package workers.zinc.compile

import com.google.devtools.build.buildjar.jarhelper.JarHelper

import java.io.{File, InputStream, OutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, Path, Paths}
import java.nio.file.attribute.FileTime
import java.util.{Optional, Map as JMap}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import sbt.internal.inc.{APIs, Analysis, PlainVirtualFile, Relations, Schema, SourceInfos, Stamper, Stamps, Stamp as StampImpl}
import sbt.internal.inc.consistent.ConsistentFileAnalysisStore
import sbt.internal.inc.text.Mapper
import sbt.internal.shaded.com.google.protobuf.{GeneratedMessageV3, Parser}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import xsbti.{PathBasedFile, VirtualFileRef}
import xsbti.compile.{AnalysisContents, AnalysisStore, CompileOptions, MiniSetup, Output}
import xsbti.compile.analysis.{GenericMapper, ReadMapper, ReadWriteMappers, Stamp, WriteMapper}
import workers.common.AnnexLogger

final case class AnalysisFiles(apis: Path, miniSetup: Path, relations: Path, sourceInfos: Path, stamps: Path)

object AnxAnalysisStore:
  sealed trait Format:
    def read[A <: GeneratedMessageV3](parser: Parser[A], inputStream: InputStream): A
    def write(message: GeneratedMessageV3, stream: OutputStream): Unit

  object BinaryFormat extends Format:
    def read[A <: GeneratedMessageV3](parser: Parser[A], stream: InputStream): A =
      parser.parseFrom(GZIPInputStream(stream))
    def write(message: GeneratedMessageV3, stream: OutputStream): Unit =
      val gzip = GZIPOutputStream(stream, true)
      message.writeTo(gzip)
      gzip.finish()

  object TextFormat extends Format:
    def read[A <: GeneratedMessageV3](parser: Parser[A], stream: InputStream): A =
      parser.parseFrom(stream)
    def write(message: GeneratedMessageV3, stream: OutputStream): Unit =
      val writer = OutputStreamWriter(stream, StandardCharsets.US_ASCII)
      try writer.write(message.toString)
      finally writer.close()

sealed trait Readable[A]:
  def read(file: Path): A

sealed trait Writeable[A]:
  def write(file: Path, value: A): Unit

final class Store[A](readStream: InputStream => A, writeStream: (OutputStream, A) => Unit) extends Readable[A] with Writeable[A]:
  def read(file: Path): A =
    val stream = Files.newInputStream(file)
    try readStream(stream)
    finally stream.close()
  def write(file: Path, value: A): Unit =
    val stream = Files.newOutputStream(file)
    try writeStream(stream, value)
    finally stream.close()

final class AnxAnalysisStore(files: AnalysisFiles, analyses: AnxAnalyses) extends AnalysisStore:
  final val Empty = Optional.empty[AnalysisContents]

  override def get: Optional[AnalysisContents] =
    try
      val analysis = Analysis.Empty.copy(
        apis = analyses.apis(files.apis).read(files.apis),
        relations = analyses.relations(files.relations).read(files.relations),
        infos = analyses.sourceInfos(files.sourceInfos).read(files.sourceInfos),
        stamps = analyses.stamps(files.stamps).read(files.stamps)
      )
      val miniSetup = analyses.miniSetup(files.miniSetup).read(files.miniSetup)
      Optional.of(AnalysisContents.create(analysis, miniSetup))
    catch case _: NoSuchFileException => Empty

  override def unsafeGet: AnalysisContents = get.get

  override def set(analysisContents: AnalysisContents): Unit =
    val analysis = analysisContents.getAnalysis.asInstanceOf[Analysis]
    analyses.apis(files.apis).write(files.apis, analysis.apis)
    analyses.relations(files.relations).write(files.relations, analysis.relations)
    analyses.sourceInfos(files.sourceInfos).write(files.sourceInfos, analysis.infos)
    analyses.stamps(files.stamps).write(files.stamps, analysis.stamps)
    val miniSetup = analysisContents.getMiniSetup
    analyses.miniSetup(files.miniSetup).write(files.miniSetup, miniSetup)

final class AnxAnalyses(format: AnxAnalysisStore.Format)(using ctx: ZincContext, logger: AnnexLogger):
  private def binaryStore(path: Path): AnalysisStore =
    ConsistentFileAnalysisStore.binary(
      file = path.toFile,
      mappers = ReadWriteMappers.getEmptyMappers,
      sort = false,
      parallelism = math.min(Runtime.getRuntime.availableProcessors(), 8)
    )

  def apis(path: Path) =
    new Store[APIs](
      _ => binaryStore(path).get
        .map(_.getAnalysis.asInstanceOf[Analysis].apis)
        .orElse(Analysis.Empty.apis),
      (_, value) => {
        val contents = binaryStore(path).get
        val miniSetup = contents.map(_.getMiniSetup).orElseThrow()
        binaryStore(path).set(AnalysisContents.create(Analysis.Empty.copy(apis = value), miniSetup))
      }
    )

  def miniSetup(path: Path) =
    new Store[MiniSetup](
      _ => binaryStore(path).get.map(_.getMiniSetup).orElseThrow(),
      (_, value) => {
        binaryStore(path).set(AnalysisContents.create(Analysis.Empty, value))
      }
    )

  def relations(path: Path) =
    new Store[Relations](
      _ => binaryStore(path).get
        .map(_.getAnalysis.asInstanceOf[Analysis].relations)
        .orElse(Analysis.Empty.relations),
      (_, value) => {
        val contents = binaryStore(path).get
        val miniSetup = contents.map(_.getMiniSetup).orElseThrow()
        binaryStore(path).set(AnalysisContents.create(Analysis.Empty.copy(relations = value), miniSetup))
      }
    )

  def sourceInfos(path: Path) =
    new Store[SourceInfos](
      _ => binaryStore(path).get.orElseThrow().getAnalysis.asInstanceOf[Analysis].infos,
      (_, value) => {
        val contents = binaryStore(path).get.orElseThrow()
        binaryStore(path).set(AnalysisContents.create(Analysis.Empty.copy(infos = value), contents.getMiniSetup))
      }
    )

  def stamps(path: Path) =
    new Store[Stamps](
      _ => binaryStore(path).get.orElseThrow().getAnalysis.asInstanceOf[Analysis].stamps,
      (_, value) => {
        val contents = binaryStore(path).get.orElseThrow()
        binaryStore(path).set(AnalysisContents.create(Analysis.Empty.copy(stamps = value), contents.getMiniSetup))
      }
    )

  private def updateAnalyzedMap(map: JMap[String, Schema.AnalyzedClass]): JMap[String, Schema.AnalyzedClass] =
    map.asScala.map { case (key, cls) =>
      key -> cls.toBuilder.setCompilationTimestamp(JarHelper.DEFAULT_TIMESTAMP).build()
    }.asJava

  private def update(api: Schema.APIs): Schema.APIs =
    api
      .toBuilder
      .putAllInternal(updateAnalyzedMap(api.getInternalMap))
      .putAllExternal(updateAnalyzedMap(api.getExternalMap))
      .build()

  // Workaround for https://github.com/sbt/zinc/pull/532
  private def update(
      mapper: GenericMapper,
      relations: Schema.Relations
  ): Schema.Relations =
    val updatedSrcProd = relations.getSrcProdMap.asScala.map { case (source, products) =>
      val values = products.getValuesList.asScala.map(path => mapper.mapProductFile(Mapper.forFileV.read(path)).toString)
      mapper.mapSourceFile(Mapper.forFileV.read(source)).toString -> Schema.Values.newBuilder().addAllValues(values.asJava).build()
    }
    val updatedLibraryDep = relations.getLibraryDepMap.asScala.map { case (source, binaries) =>
      val values = binaries.getValuesList.asScala.map(path => mapper.mapBinaryFile(Mapper.forFileV.read(path)).toString)
      mapper
        .mapBinaryFile(mapper.mapSourceFile(Mapper.forFileV.read(source)))
        .toString -> Schema.Values.newBuilder().addAllValues(values.asJava).build()
    }
    val updatedClasses = relations.getClassesMap.asScala.map { case (source, values) =>
      mapper.mapSourceFile(Mapper.forFileV.read(source)).toString -> values
    }
    relations.toBuilder.putAllSrcProd(updatedSrcProd.asJava).putAllLibraryDep(updatedLibraryDep.asJava).putAllClasses(updatedClasses.asJava).build()

object AnxMapper:
  val rootPlaceholder = Paths.get("_ROOT_")
  val tmpPlaceholder = Paths.get("_TMP_")

  def mappers()(using ctx: ZincContext, logger: AnnexLogger) = ReadWriteMappers(AnxReadMapper(), AnxWriteMapper())

  def hashStamp(file: Path): Stamp =
    val newTime = Files.getLastModifiedTime(file)
    stampCache.get(file) match
      case Some((time, stamp)) if newTime.compareTo(time) <= 0 => stamp
      case _ =>
        val stamp = Stamper.forFarmHashP(file)
        stampCache += (file -> (newTime, stamp))
        stamp

  private val stampCache = mutable.HashMap.empty[Path, (FileTime, Stamp)]

final class AnxWriteMapper()(using ctx: ZincContext) extends WriteMapper:
  private def mapFile(file: Path): Path =
    if file.startsWith(ctx.tmpDir) then AnxMapper.tmpPlaceholder.resolve(ctx.tmpDir.relativize(file))
    else if file.startsWith(ctx.rootDir) then AnxMapper.rootPlaceholder.resolve(ctx.rootDir.relativize(file))
    else file

  private def mapFile(file: VirtualFileRef): VirtualFileRef =
    val path = file match
      case file: PathBasedFile => file.toPath
      case _                   => Paths.get(file.toString)
    PlainVirtualFile(mapFile(path))

  override def mapSourceFile(sourceFile: VirtualFileRef): VirtualFileRef = mapFile(sourceFile)
  override def mapBinaryFile(binaryFile: VirtualFileRef): VirtualFileRef = mapFile(binaryFile)
  override def mapProductFile(productFile: VirtualFileRef): VirtualFileRef = mapFile(productFile)

  override def mapClasspathEntry(classpathEntry: Path): Path = mapFile(classpathEntry)
  override def mapJavacOption(javacOption: String): String = javacOption
  override def mapScalacOption(scalacOption: String): String = scalacOption

  override def mapOutputDir(outputDir: Path): Path = mapFile(outputDir)
  override def mapSourceDir(sourceDir: Path): Path = mapFile(sourceDir)

  override def mapProductStamp(file: VirtualFileRef, productStamp: Stamp): Stamp = productStamp
  // StampImpl.fromString(s"lastModified(${JarHelper.DEFAULT_TIMESTAMP})")
  override def mapSourceStamp(file: VirtualFileRef, sourceStamp: Stamp): Stamp = sourceStamp
  override def mapBinaryStamp(file: VirtualFileRef, binaryStamp: Stamp): Stamp = binaryStamp

  override def mapMiniSetup(miniSetup: MiniSetup): MiniSetup = miniSetup

final class AnxReadMapper()(using ctx: ZincContext, logger: AnnexLogger) extends ReadMapper:
  private def mapFile(file: Path): Path =
    val path =
      if file.startsWith(AnxMapper.tmpPlaceholder) then ctx.tmpDir.resolve(AnxMapper.tmpPlaceholder.relativize(file))
      else if file.startsWith(AnxMapper.rootPlaceholder) then ctx.rootDir.resolve(AnxMapper.rootPlaceholder.relativize(file))
      else file
    if !path.toFile().exists() then logger.debug(() => s"File `$path` not found")
    path

  private def mapFile(file: VirtualFileRef): VirtualFileRef =
    val path = file match
      case file: PathBasedFile => file.toPath
      case _                   => Paths.get(file.toString)
    PlainVirtualFile(mapFile(path))

  override def mapSourceFile(sourceFile: VirtualFileRef): VirtualFileRef = mapFile(sourceFile)
  override def mapBinaryFile(binaryFile: VirtualFileRef): VirtualFileRef = mapFile(binaryFile)
  override def mapProductFile(productFile: VirtualFileRef): VirtualFileRef = mapFile(productFile)

  override def mapClasspathEntry(classpathEntry: Path): Path = mapFile(classpathEntry)
  override def mapJavacOption(javacOption: String): String = javacOption
  override def mapScalacOption(scalacOption: String): String = scalacOption

  override def mapOutputDir(outputDir: Path): Path = mapFile(outputDir)
  override def mapSourceDir(sourceDir: Path): Path = mapFile(sourceDir)

  override def mapProductStamp(file: VirtualFileRef, productStamp: Stamp): Stamp = productStamp
  // file match
  //   case file: PathBasedFile => Stamper.forLastModifiedP(file.toPath())
  //   case _                   => productStamp
  override def mapSourceStamp(file: VirtualFileRef, sourceStamp: Stamp): Stamp = sourceStamp
  override def mapBinaryStamp(file: VirtualFileRef, binaryStamp: Stamp): Stamp = binaryStamp
  // val res = file match
  //   case file: PathBasedFile =>
  //     val filePath = file.toPath()
  //     if AnxMapper.hashStamp(filePath) == binaryStamp then Stamper.forLastModifiedP(filePath)
  //     else binaryStamp
  //   case _ => binaryStamp
  // println(s"mapBinaryStamp file:$file binaryStamp:$binaryStamp res:$res")
  // res

  override def mapMiniSetup(miniSetup: MiniSetup): MiniSetup = miniSetup
