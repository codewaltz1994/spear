package scraper.expressions.typecheck

import scala.util.Try

import scraper.exceptions.TypeMismatchException
import scraper.expressions.Cast.{promoteDataType, widestTypeOf}
import scraper.expressions.Expression
import scraper.types.{AbstractDataType, DataType}
import scraper.utils.trySequence

/**
 * A trait that helps in both type checking and type coercion for expression arguments.
 */
sealed trait TypeConstraint {
  def strictlyTyped: Try[Seq[Expression]]

  def ~(that: TypeConstraint): Concat = Concat(this, that)
}

/**
 * A [[TypeConstraint]] that simply turns all argument expressions to their strictly-typed form.
 * This is the default type constraint for simple expressions like [[Alias]].
 */
case class PassThrough(args: Seq[Expression]) extends TypeConstraint {
  override def strictlyTyped: Try[Seq[Expression]] = trySequence(args map (_.strictlyTyped))
}

/**
 * A [[TypeConstraint]] that
 *
 *  - turns all argument expressions to strictly-typed form, then
 *  - checks data type of each strictly-typed argument expression `e`:
 *    - if `e.dataType` equals to `targetType`, leaves it untouched;
 *    - otherwise, throws a [[TypeMismatchException]].
 */
case class Exact(targetType: DataType, args: Seq[Expression]) extends TypeConstraint {
  override def strictlyTyped: Try[Seq[Expression]] = for {
    strictArgs <- trySequence(args map (_.strictlyTyped))
  } yield strictArgs map {
    case `targetType`(e) => e
    case e               => throw new TypeMismatchException(e, targetType)
  }
}

/**
 * A [[TypeConstraint]] that
 *
 *  - turns all argument expressions to strictly-typed form, then
 *  - checks data type of each strictly-typed argument expression `e`:
 *    - if `e.dataType` equals to `targetType`, leaves it untouched;
 *    - if `e.dataType` is implicitly convertible to `targetType`, casts `e` to `targetType`;
 *    - otherwise, throws a [[TypeMismatchException]].
 */
case class ImplicitlyConvertibleTo(targetType: DataType, args: Seq[Expression])
  extends TypeConstraint {

  override def strictlyTyped: Try[Seq[Expression]] = for {
    strictArgs <- trySequence(args map (_.strictlyTyped))
  } yield strictArgs map {
    case `targetType`(e)            => e
    case `targetType`.Implicitly(e) => promoteDataType(e, targetType)
    case e                          => throw new TypeMismatchException(e, targetType)
  }
}

case class SubtypeOf(superType: AbstractDataType, args: Seq[Expression]) extends TypeConstraint {
  override def strictlyTyped: Try[Seq[Expression]] = for {
    strictArgs <- trySequence(args map (_.strictlyTyped))
    candidates = strictArgs collect { case `superType`(e) => e }
    widestSubType <- if (candidates.nonEmpty) {
      widestTypeOf(candidates map (_.dataType))
    } else {
      throw new TypeMismatchException(args.head, superType)
    }
  } yield strictArgs map (promoteDataType(_, widestSubType))
}

case class CompatibleWith(target: Expression, args: Seq[Expression]) extends TypeConstraint {
  override def strictlyTyped: Try[Seq[Expression]] = for {
    strictTarget <- target.strictlyTyped
    targetType = strictTarget.dataType
    strictArgs <- trySequence(args map (_.strictlyTyped))
  } yield strictArgs map {
    case `targetType`(e)            => e
    case `targetType`.Implicitly(e) => promoteDataType(e, targetType)
    case e                          => throw new TypeMismatchException(e, targetType)
  }
}

case class AllCompatible(args: Seq[Expression]) extends TypeConstraint {
  override def strictlyTyped: Try[Seq[Expression]] = for {
    strictArgs <- trySequence(args map (_.strictlyTyped))
    widestType <- widestTypeOf(strictArgs map (_.dataType))
  } yield strictArgs map (promoteDataType(_, widestType))
}

/**
 * A [[TypeConstraint]] that concatenates results of two [[TypeConstraint]]s.
 */
case class Concat(left: TypeConstraint, right: TypeConstraint) extends TypeConstraint {
  override def strictlyTyped: Try[Seq[Expression]] = for {
    strictLeft <- left.strictlyTyped
    strictRight <- right.strictlyTyped
  } yield strictLeft ++ strictRight
}
