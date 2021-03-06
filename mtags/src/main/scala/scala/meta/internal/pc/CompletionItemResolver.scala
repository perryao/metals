package scala.meta.internal.pc

import org.eclipse.lsp4j.CompletionItem
import scala.collection.JavaConverters._
import scala.meta.internal.mtags.MtagsEnrichments._

class CompletionItemResolver(
    val compiler: MetalsGlobal
) {
  import compiler._
  def resolve(item: CompletionItem, msym: String): CompletionItem = {
    val gsym = inverseSemanticdbSymbol(msym)
    if (gsym != NoSymbol) {
      symbolDocumentation(gsym).orElse(symbolDocumentation(gsym.companion)) match {
        case Some(info) if item.getDetail != null =>
          if (isJavaSymbol(gsym)) {
            val newDetail = info
              .parameters()
              .asScala
              .iterator
              .zipWithIndex
              .foldLeft(item.getDetail) {
                case (detail, (param, i)) =>
                  detail.replaceAllLiterally(
                    s"x$$${i + 1}",
                    param.displayName()
                  )
              }
            item.setDetail(newDetail)
          } else {
            val defaults = info
              .parameters()
              .asScala
              .iterator
              .map(_.defaultValue())
              .filterNot(_.isEmpty)
            val matcher = "= \\{\\}".r.pattern.matcher(item.getDetail)
            val out = new StringBuffer()
            while (matcher.find()) {
              if (defaults.hasNext) {
                matcher.appendReplacement(out, s"= ${defaults.next()}")
              }
            }
            matcher.appendTail(out)
            item.setDetail(out.toString)
          }
          val docstring = fullDocstring(gsym)
          item.setDocumentation(docstring.toMarkupContent)
        case _ =>
      }
      item
    } else {
      item
    }
  }

  def fullDocstring(gsym: Symbol): String = {
    def docs(gsym: Symbol): String =
      symbolDocumentation(gsym).fold("")(_.docstring())
    val gsymDoc = docs(gsym)
    def keyword(gsym: Symbol): String =
      if (gsym.isClass) "class"
      else if (gsym.isTrait) "trait"
      else if (gsym.isJavaInterface) "interface"
      else if (gsym.isModule) "object"
      else ""
    val companion = gsym.companion
    if (companion == NoSymbol || isJavaSymbol(gsym)) {
      if (gsymDoc.isEmpty) {
        if (gsym.isAliasType) {
          fullDocstring(gsym.info.dealias.typeSymbol)
        } else if (gsym.isMethod) {
          gsym.info.finalResultType match {
            case SingleType(_, sym) =>
              fullDocstring(sym)
            case _ =>
              ""
          }
        } else ""
      } else {
        gsymDoc
      }
    } else {
      val companionDoc = docs(companion)
      if (companionDoc.isEmpty) gsymDoc
      else if (gsymDoc.isEmpty) companionDoc
      else {
        List(
          s"""|### ${keyword(companion)} ${companion.name}
              |$companionDoc
              |""".stripMargin,
          s"""|### ${keyword(gsym)} ${gsym.name}
              |${gsymDoc}
              |""".stripMargin
        ).sorted.mkString("\n")
      }
    }
  }
}
