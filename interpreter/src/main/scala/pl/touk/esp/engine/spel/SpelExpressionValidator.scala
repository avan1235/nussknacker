package pl.touk.esp.engine.spel

import cats.data.Validated
import org.springframework.expression.Expression
import org.springframework.expression.spel.ast._
import org.springframework.expression.spel.{SpelNode, standard}
import pl.touk.esp.engine.compile.{ValidatedSyntax, ValidationContext}
import pl.touk.esp.engine.compiledgraph.expression
import pl.touk.esp.engine.compiledgraph.expression.ExpressionParseError
import pl.touk.esp.engine.definition.DefinitionExtractor.ClazzRef

class SpelExpressionValidator(expr: Expression, ctx: ValidationContext) {
  import SpelExpressionValidator._
  import cats.instances.list._

  private val syntax = ValidatedSyntax[ExpressionParseError]
  import syntax._

  private val ignoredTypes: Set[ClazzRef] = Set(
    classOf[java.util.Map[_, _]],
    classOf[scala.collection.convert.Wrappers.MapWrapper[_, _]],
    classOf[Any]
  ).map(ClazzRef.apply)

  def validate(): Validated[ExpressionParseError, Expression] = {
    val ast = expr.asInstanceOf[standard.SpelExpression].getAST
    resolveReferences(ast).andThen { _ =>
      findAllPropertyAccess(ast, None).andThen { propertyAccesses =>
        propertyAccesses.flatMap(validatePropertyAccess).headOption match {
          case Some(error) => Validated.invalid(error)
          case None => Validated.valid(expr)
        }
      }
    }
  }

  private def resolveReferences(node: SpelNode): Validated[ExpressionParseError, Expression] = {
    val references = findAllVariableReferences(node)
    val notResolved = references.filterNot(ctx.contains)
    if (notResolved.isEmpty) Validated.valid(expr)
    else Validated.Invalid(ExpressionParseError(s"Unresolved references ${notResolved.mkString(", ")}"))
  }

  private def findAllPropertyAccess(n: SpelNode, rootClass: Option[ClazzRef]): Validated[ExpressionParseError, List[SpelPropertyAccess]] = {
    n match {
      case ce: CompoundExpression if ce.childrenHead.isInstanceOf[VariableReference] =>
        findVariableReferenceAccess(ce.childrenHead.asInstanceOf[VariableReference], ce.children.tail.toList)
      //TODO: walidacja zmiennych w srodku Projection/Selection
      case ce: CompoundExpression if ce.childrenHead.isInstanceOf[PropertyOrFieldReference] =>
        Validated.invalid(ExpressionParseError(s"Non reference '${ce.childrenHead.toStringAST}' occurred. Maybe you missed '#' in front of it?"))
      case prop: PropertyOrFieldReference if rootClass.isEmpty =>
        Validated.invalid(ExpressionParseError(s"Non reference '${prop.toStringAST}' occurred. Maybe you missed '#' in front of it?"))
      //TODO: walidacja zmiennych w srodku Projection/Selection, ale wtedy musielibysmy znac typ zawartosci listy...
      case prop: Projection =>
        validateChildren(n.children, Some(ClazzRef(classOf[Object])))
      case sel: Selection =>
        validateChildren(n.children, Some(ClazzRef(classOf[Object])))
      case map: InlineMap =>
        //we take only odd indices, even ones are map keys...
        validateChildren(n.children.zipWithIndex.filter(_._2 % 2 == 1).map(_._1), None)
      case _ =>
        validateChildren(n.children, None)
    }
  }

  private def validateChildren(children: Seq[SpelNode], rootClass: Option[ClazzRef]): Validated[expression.ExpressionParseError, List[SpelPropertyAccess]] = {
    val accessesWithErrors = children.toList.map { child => findAllPropertyAccess(child, rootClass).toValidatedNel }.sequence
    accessesWithErrors.map(_.flatten).leftMap(_.head)
  }

  private def findVariableReferenceAccess(reference: VariableReference, children: List[SpelNode] = List()) = {
    val variableName = reference.toStringAST.tail
    val references = children.takeWhile(_.isInstanceOf[PropertyOrFieldReference]).map(_.toStringAST) //nie bierzemy jeszcze wszystkich co nie jest do konca poprawnne, np w `#obj.children.?[id == '55'].empty`
    val clazzRef = ctx.apply(variableName)
    if (ignoredTypes.contains(clazzRef)) Validated.valid(List.empty) //odpuszczamy na razie walidowanie spelowych Map i wtedy kiedy nie jestesmy pewni typu
    else {
      Validated.valid(List(SpelPropertyAccess(variableName, references, ctx.apply(variableName))))
    }
  }


  private def validatePropertyAccess(propAccess: SpelPropertyAccess): Option[ExpressionParseError] = {
    def checkIfPropertiesExistsOnClass(propsToGo: List[String], clazz: ClazzRef): Option[ExpressionParseError] = {
      if (propsToGo.isEmpty) None
      else {
        val currentProp = propsToGo.head
        val typeInfo = ctx.getTypeInfo(clazz)
        typeInfo.getMethod(currentProp) match {
          case Some(anotherClazzRef) => checkIfPropertiesExistsOnClass(propsToGo.tail, anotherClazzRef)
          case None => Some(ExpressionParseError(s"There is no property '$currentProp' in type '${typeInfo.clazzName.refClazzName}'"))
        }
      }
    }
    checkIfPropertiesExistsOnClass(propAccess.properties, propAccess.clazz)
  }

  private def findAllVariableReferences(n: SpelNode): List[String] = {
    if (n.getChildCount == 0) {
      n match {
        case vr: VariableReference => List(vr.toStringAST.tail)
        case _ => List()
      }
    }
    else n.children.flatMap(findAllVariableReferences).toList
  }

}

object SpelExpressionValidator {

  implicit class RichSpelNode(n: SpelNode) {
    def children: Seq[SpelNode] = {
      (0 until n.getChildCount).map(i => n.getChild(i))
    }
    def childrenHead: SpelNode = {
      n.getChild(0)
    }

  }

  case class SpelPropertyAccess(variable: String, properties: List[String], clazz: ClazzRef)
}