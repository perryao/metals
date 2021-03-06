package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextDocumentPositionParams
import scala.concurrent.ExecutionContextExecutorService
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.LogMessages
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch
import scala.tools.nsc.Properties

/**
 * Manages lifecycle for presentation compilers in all build targets.
 *
 * We need a custom presentation compiler for each build target since
 * build targets can have different classpaths and compiler settings.
 */
class Compilers(
    workspace: AbsolutePath,
    config: MetalsServerConfig,
    buildTargets: BuildTargets,
    buffers: Buffers,
    search: SymbolSearch,
    embedded: Embedded,
    statusBar: StatusBar,
    sh: ScheduledExecutorService
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {
  val plugins = new CompilerPlugins()

  // Not a TrieMap because we want to avoid loading duplicate compilers for the same build target.
  // Not a `j.u.c.ConcurrentHashMap` because it can deadlock in `computeIfAbsent` when the absent
  // function is expensive, which is the case here.
  val jcache = Collections.synchronizedMap(
    new java.util.HashMap[BuildTargetIdentifier, PresentationCompiler]
  )
  private val cache = jcache.asScala

  override def cancel(): Unit = {
    Cancelable.cancelEach(cache.values)(_.shutdown())
    cache.clear()
  }
  def restartAll(): Unit = {
    val count = cache.size
    cancel()
    scribe.info(
      s"restarted ${count} presentation compiler${LogMessages.plural(count)}"
    )
  }
  def didCompileSuccessfully(id: BuildTargetIdentifier): Unit = {
    cache.remove(id).foreach(_.shutdown())
  }

  def completionItemResolve(
      item: CompletionItem,
      token: CancelToken
  ): Option[CompletionItem] = {
    for {
      data <- item.data
      compiler <- cache.get(new BuildTargetIdentifier(data.target))
    } yield compiler.completionItemResolve(item, data.symbol)
  }

  def log: List[String] =
    if (config.compilers.debug) {
      List(
        "-Ypresentation-debug",
        "-Ypresentation-verbose",
        "-Ypresentation-log",
        workspace.resolve(Directories.pc).toString()
      )
    } else {
      Nil
    }
  def completions(
      params: CompletionParams,
      token: CancelToken
  ): Option[CompletionList] =
    withPC(params) { (pc, pos) =>
      pc.complete(
        CompilerOffsetParams(pos.input.syntax, pos.input.text, pos.start, token)
      )
    }
  def hover(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Option[Hover] =
    withPC(params) { (pc, pos) =>
      pc.hoverForDebuggingPurposes(
        CompilerOffsetParams(pos.input.syntax, pos.input.text, pos.start, token)
      )
    }
  def signatureHelp(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Option[SignatureHelp] =
    withPC(params) { (pc, pos) =>
      pc.signatureHelp(
        CompilerOffsetParams(pos.input.syntax, pos.input.text, pos.start, token)
      )
    }

  private def loadCompiler(path: AbsolutePath): Option[PresentationCompiler] = {
    for {
      target <- buildTargets.inverseSources(path)
      info <- buildTargets.info(target)
      scala <- info.asScalaBuildTarget
      isSupported = ScalaVersions.isSupportedScalaVersion(scala.getScalaVersion)
      _ = {
        if (!isSupported) {
          scribe.warn(s"unsupported Scala ${scala.getScalaVersion}")
        }
      }
      if isSupported
      scalac <- buildTargets.scalacOptions(target)
    } yield {
      jcache.computeIfAbsent(
        target, { _ =>
          statusBar.trackBlockingTask(
            s"${statusBar.icons.sync}Loading presentation compiler"
          ) {
            newCompiler(scalac, scala)
          }
        }
      )
    }
  }

  private def withPC[T](
      params: TextDocumentPositionParams
  )(fn: (PresentationCompiler, Position) => T): Option[T] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    loadCompiler(path).map { compiler =>
      val input = path.toInputFromBuffers(buffers)
      val pos = params.getPosition.toMeta(input)
      val result = fn(compiler, pos)
      result
    }
  }

  def newCompiler(
      scalac: ScalacOptionsItem,
      info: ScalaBuildTarget
  ): PresentationCompiler = {
    val classpath = scalac.classpath.map(_.toNIO).toSeq
    val pc: PresentationCompiler =
      if (info.getScalaVersion == Properties.versionNumberString) {
        new ScalaPresentationCompiler()
      } else {
        embedded.presentationCompiler(info, scalac)
      }
    val options = plugins.filterSupportedOptions(scalac.getOptions.asScala)
    pc.withSearch(search)
      .withExecutorService(ec)
      .withScheduledExecutorService(sh)
      .withConfiguration(config.compilers)
      .newInstance(
        scalac.getTarget.getUri,
        classpath.asJava,
        (log ++ options).asJava
      )
  }
}
