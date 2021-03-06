package slick.compiler

import slick.ast._
import Util._
import TypeUtil._

/** Rewrite zip joins into a form suitable for SQL using inner joins and RowNumber columns.
  *
  * We rely on having a Bind around every Join and both of its generators, which should have been
  * generated by Phase.forceOuterBinds. The inner Binds need to select `Pure(StructNode(...))`
  * which should be the outcome of Phase.flattenProjections.
  *
  * @param rownumStyle Whether to use `Subquery` boundaries suitable for Oracle-style ROWNUM
  *                    semantics instead of standard ROW_NUMBER(). */
class ResolveZipJoins(rownumStyle: Boolean = false) extends Phase {
  type State = Boolean
  val name = "resolveZipJoins"

  val condAbove: Subquery.Condition = if(rownumStyle) Subquery.AboveRownum else Subquery.AboveRowNumber
  val condBelow: Subquery.Condition = if(rownumStyle) Subquery.BelowRownum else Subquery.BelowRowNumber

  def apply(state: CompilerState) = {
    val n2 = state.tree.replace({
      case b @ Bind(s1,
          Join(_, _, Bind(ls, from, Pure(StructNode(defs), _)), RangeFrom(offset), JoinType.Zip, LiteralNode(true)),
          p) =>
        logger.debug("Transforming zipWithIndex:", b)
        val b2 = transformZipWithIndex(s1, ls, from, defs, offset, p)
        logger.debug("Transformed zipWithIndex:", b2)
        b2
      case b @ Bind(s1, Join(jlsym, jrsym,
          l @ Bind(_, _, Pure(StructNode(ldefs), _)),
          r @ Bind(_, _, Pure(StructNode(rdefs), _)),
          JoinType.Zip, LiteralNode(true)), sel) =>
        logger.debug("Transforming zip:", b)
        val b2 = transformZip(s1, jlsym, jrsym, l, ldefs, r, rdefs, sel)
        logger.debug("Transformed zip:", b2)
        b2
    }, bottomUp = true).infer()
    state + (this -> (n2 ne state.tree)) withNode n2
  }

  /** Transform a `zipWithIndex` operation of the form
    * `Bind(s1, Join(_, _, Bind(ls, from, Pure(StructNode(defs), _)), RangeFrom(offset), JoinType.Zip, LiteralNode(true)), p)`
    * into an equivalent mapping operation using `RowNum`. This method can be overridden in
    * subclasses to implement non-standard translations. */
  def transformZipWithIndex(s1: TermSymbol, ls: TermSymbol, from: Node,
                            defs: IndexedSeq[(TermSymbol, Node)], offset: Long, p: Node): Node = {
    val idxSym = new AnonSymbol
    val idxExpr =
      if(offset == 1L) RowNumber()
      else Library.-.typed[Long](RowNumber(), LiteralNode(1L - offset))
    val lbind = Bind(ls, Subquery(from, condBelow), Pure(StructNode(defs :+ (idxSym, idxExpr))))
    Bind(s1, Subquery(lbind, condAbove), p.replace {
      case Select(Ref(s), ElementSymbol(1)) if s == s1 => Ref(s1)
      case Select(Ref(s), ElementSymbol(2)) if s == s1 => Select(Ref(s1), idxSym)
    }).infer()
  }

  /** Transform a `zip` operation of the form
    * `Bind(s1, Join(jlsym, jrsym, l @ Bind(_, _, Pure(StructNode(ldefs), _)), r @ Bind(_, _, Pure(StructNode(rdefs), _)), JoinType.Zip, LiteralNode(true)), sel)`
    * into an equivalent mapping operation using `RowNum` by first transforming both sides of the
    * join into `zipWithIndex` and then using `transformZipWithIndex` on those. */
  def transformZip(s1: TermSymbol, jlsym: TermSymbol, jrsym: TermSymbol,
                   l: Bind, ldefs: IndexedSeq[(TermSymbol, Node)],
                   r: Bind, rdefs: IndexedSeq[(TermSymbol, Node)], sel: Node): Node = {
    val lmap = ldefs.map(t => (t._1, new AnonSymbol)).toMap
    val rmap = rdefs.map(t => (t._1, new AnonSymbol)).toMap
    val lisym, risym, l2sym, r2sym = new AnonSymbol
    val l2 = transformZipWithIndex(l2sym, l.generator, l.from, ldefs, 1L,
      Pure(StructNode(ldefs.map { case (f, _) =>
        (lmap(f) -> FwdPath(List(l2sym, ElementSymbol(1), f))) } :+
        (lisym -> FwdPath(List(l2sym, ElementSymbol(2)))) ))
    )
    val r2 = transformZipWithIndex(r2sym, r.generator, r.from, rdefs, 1L,
      Pure(StructNode(rdefs.map { case (f, _) =>
        (rmap(f) -> FwdPath(List(r2sym, ElementSymbol(1), f))) } :+
        (risym -> FwdPath(List(r2sym, ElementSymbol(2)))) ))
    )
    val j2 = Join(jlsym, jrsym, l2, r2, JoinType.Inner,
      Library.==.typed[Boolean](Select(Ref(jlsym), lisym), Select(Ref(jrsym), risym)))
    Bind(s1, j2, sel.replace {
      case FwdPath(Seq(s, ElementSymbol(1), f)) if s == s1 => FwdPath(List(s, ElementSymbol(1), lmap(f)))
      case FwdPath(Seq(s, ElementSymbol(2), f)) if s == s1 => FwdPath(List(s, ElementSymbol(2), rmap(f)))
    }).infer()
  }
}
