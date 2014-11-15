package scala.slick.compiler

import scala.slick.ast._
import Util._
import TypeUtil._
import scala.reflect.ClassTag
import scala.slick.SlickException

/** Create a ResultSetMapping root node, ensure that the top-level server-side
  * node returns a collection, and hoist client-side type conversions into the
  * ResultSetMapping. */
class CreateResultSetMapping extends Phase {
  val name = "createResultSetMapping"

  def apply(state: CompilerState) = state.map { n =>
    logger.debug("Client-side result type: "+n.nodeType)
    val n2 = removeTypeMapping(n)
    logger.debug("Removed type mapping:", n2)
    val n3 = toCollection(n2)
    logger.debug("Converted to collection:", n3)
    ClientSideOp.mapServerSide(n3) { ch =>
      val gen = new AnonSymbol
      ResultSetMapping(gen, ch, createResult(gen, n.nodeType match {
        case CollectionType(_, el) => el
        case t => t
      }))
    }
  }

  /** Remove TypeMapping nodes and MappedTypes */
  def removeTypeMapping(n: Node): Node = n match {
    case t: TypeMapping => removeTypeMapping(t.child)
    case n =>
      val n2 = n.nodeMapChildren(removeTypeMapping)
      val tpe = n2.nodeType
      val tpe2 = removeMappedType(tpe)
      if(tpe2 eq tpe) n2 else n2.nodeTypedOrCopy(tpe2)
  }

  /** Force collection return type. This might get more complicated in the
    * future. For now, all primitive types should be set (but collection-typed
    * nodes and product nodes may have UnassignedType) and ProductNodes cannot
    * contain nested collections. */
  def toCollection(n: Node): Node = n match {
    case _: Apply | _: ProductNode => First(Pure(n))
    case n if n.nodeType != UnassignedType && !n.nodeType.isInstanceOf[CollectionType] => First(Pure(n))
    case n => n
  }

  /** Remove MappedTypes from a Type */
  def removeMappedType(tpe: Type): Type = tpe match {
    case m: MappedScalaType => removeMappedType(m.baseType)
    case t => t.mapChildren(removeMappedType)
  }

  /** Create a structured return value for the client side, based on the
    * result type (which may contain MappedTypes). References are created
    * to linearized columns and not to the real structure. This keeps further
    * compiler phases simple because we don't have to do any rewriting here. We
    * just need to be careful not to typecheck the resulting tree or resolve
    * those refs until the server-side tree has been flattened. In the future
    * we may want to create the real refs and rewrite them later in order to
    * gain the ability to optimize the set of columns in the result set. */
  def createResult(sym: Symbol, tpe: Type): Node = {
    var curIdx = 0
    def f(tpe: Type): Node = {
      logger.debug("Creating mapping from "+tpe)
      tpe.structural match {
        case ProductType(ch) =>
          ProductNode(ch.map(f))
        case StructType(ch) =>
          ProductNode(ch.map { case (_, t) => f(t) })
        case t: MappedScalaType =>
          TypeMapping(f(t.baseType), t.mapper, t.classTag)
        case o @ OptionType(Type.Structural(el)) if el.children.nonEmpty =>
          val discriminator = f(ScalaBaseType.intType.optionType)
          val data = f(o.elementType)
          RebuildOption(discriminator, data)
        case t =>
          curIdx += 1
          // Assign the original type. Inside a RebuildOption the actual column type will always be
          // Option-lifted but we can still treat it as the base type when the discriminator matches.
          Library.SilentCast.typed(t.structuralRec, Select(Ref(sym), ElementSymbol(curIdx)))
      }
    }
    f(tpe)
  }
}
