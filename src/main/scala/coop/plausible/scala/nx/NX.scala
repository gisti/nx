/*
 * Copyright (c) 2014 Plausible Labs Cooperative, Inc.
 * All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package coop.plausible.scala.nx

import scala.reflect.api.{Annotations, Universe}
import scala.annotation.tailrec

/**
 * No Exceptions.
 */
object NX {
  import scala.language.experimental.macros

  /**
   * Scan `expr` for unhandled exception errors. Compiler errors will be triggered for any unhandled exceptions.
   *
   * @param expr The expression to be scanned.
   * @tparam T The expression type.
   * @return The expression result, or a compiler error if the expression contained unchecked exceptions.
   */
  def nx[T] (expr: T): T = macro NXMacro.nx_macro[T]

  /**
   * Validate `expr` and return the full validation results; rather than triggering compilation errors,
   * this simply returns the result of the validation.
   *
   * Example usage:
   * {{{
   *   val result: ValidationResult = NX.check {
   *      java.inet.InetAddress.getByName("some host")
   *   }
   * }}}
   *
   * Since java.inet.InetAddress.getByName() declares that it throws an UnknownHostException, the result
   * of NX.check will be a ValidationResult(errors, Set(classOf[UnknownHostException])).
   *
   * @param expr The expression to be scanned.
   * @tparam T The expression type.
   * @return The validation result.
   */
  private[nx] def validate[T] (expr: T): ValidationResult = macro NXMacro.nx_macro_validate[T]

  /**
   * Validate `expr` and return the set of unhandled exception types.
   *
   * This is equivelant to:
   * {{{
   *   NX.check {expr}.unhandled
   * }}}
   *
   * @param expr The expression to be scanned.
   * @tparam T The expression type.
   * @return The set of unhandled exceptions found in `expr`
   */
  private[nx] def unhandled[T] (expr: T): Set[Class[_ <: Throwable]] = macro NXMacro.nx_macro_unhandled[T]
}

/**
 * No Exceptions Implementation.
 *
 * This trait may be mixed in with any valid reflection global, including:
 * - As a compiler plugin (see [[NXPlugin]])
 * - As a compile-time macro (see [[NXMacro]])
 */
private trait NX extends Core with Errors {
  import universe._

  /**
   * Finds all unhandled throwables at a given tree node.
   */
  class ThrowableValidator {
    /**
     * Traverse `tree` and return all validation errors. Note that this will contain '''all''' unhandled `Throwable`
     * types, not just subclasses of `Exception`.
     *
     * The top-level node is treated as an exception propagation point; any exceptions that could be thrown
     * at the top of the tree are treated as unhandled exceptions.
     *
     * For example, given the following input, a value of Seq[UnhandledThrowable(... IOException)] will be provided.
     *
     * {{{
     *   @throws[IOException]("Bad data triggers failure") read () = ???
     *   read()
     * }}}
     *
     * @param tree The top-level node to be traversed.
     * @return An ordered sequence of validation errors.
     */
    def check (tree: Tree): Seq[ValidationError] = {
      /* Instantiate our traverse handler */
      val traverse = new ThrowableTraversal()

      /* Perform the traversal */
      traverse.validationErrors(tree)
    }
  }

  /**
   * Handles the actual (mutable) traversal of the tree.
   */
  private class ThrowableTraversal extends Traverser {
    import scala.collection.mutable

    /**
     * Traverse `tree` and return all validation errors. The top-level node is treated
     * as an exception propagation point; any exceptions that could be thrown at the top of the tree
     * are treated as unhandled exceptions.

     * @param tree The top-level node to be traversed.
     * @return An ordered sequence of validation errors.
     */
    def validationErrors (tree: Tree): Seq[ValidationError] = {
      /* Perform traversal */
      traverse(tree)

      /* The top of the tree is considered a propagation point */
      mutableState.declarePropagationPoint(Set())

      /* Provide the result */
      mutableState.validationErrors
    }

    /**
     * Represents a candidate throwing entity that may be caught or declared.
     *
     * @param pos The position at which the throwable may be raised.
     * @param tpe The throwable's type.
     */
    private case class Throwie (pos: Position, tpe: Type)

    /**
     * Mutable state required by the Traverser API. We vend a set of high-level APIs for operating on this state,
     * as to minimize the mutability headache.
     */
    private object mutableState {
      /**
       * The set of throwables types that are currently known to be throwable from the current position in the tree,
       * but may still be caught or declared.
       */
      private var candidateThrowies = Seq[Throwie]()

      /**
       * The set of validation errors that are found across the entire tree. This will be populated explicitly
       * during walking, as well as from the `candidateThrowies` at throwable propagation points.
       */
      private val validationErrorList = mutable.MutableList[ValidationError]()

      /**
       * Discard all candidate throwables that are a valid (sub)type of one of `throwTypes`
       *
       * @param throwTypes Types (and transitively, subtypes) to be removed from the set of candidate throwables.
       */
      private def filterCandidateThrowies (throwTypes: Set[Type]): Unit = throwTypes.foreach { throwType =>
        candidateThrowies = candidateThrowies.filterNot(_.tpe <:< throwType)
      }

      /**
       * Declare an explicit validation error. This will be added to the set of validation errors
       * available upon completion of a traversal.
       */
      def declareValidationError (error: ValidationError): Unit = {
        validationErrorList += error
      }

      /**
       * Declare one or more candidate throwables at the given point in the tree.
       *
       * @param throwies The throwies that may be thrown at this point.
       */
      def declareCandidateThrowies (throwies: Seq[Throwie]): Unit = {
        candidateThrowies = candidateThrowies ++: throwies
      }

      /**
       * This method should be called at catch pints to declare any caught throwable types (eg, from try-catch blocks).
       *
       * Any throwable types declared in `throwTypes` will be removed from the set of ''candidate'' uncaught throwables.
       *
       * @param throwTypes The throwable types declared as caught at this point.
       */
      def declareCatchPoint (throwTypes: Set[Type]): Unit = filterCandidateThrowies(throwTypes)

      /**
       * This should be called at propagation points to declare handled throwables at a propagation point.
       *
       * Any throwable types declared in `throwTypes` will be removed from the set of ''candidate'' uncaught throwables,
       * and any undeclared types will be moved to the list of ''known'' unhandled throwables.
       *
       * @param throwTypes The throwable types declared at this propagation point.
       */
      def declarePropagationPoint (throwTypes: Set[Type]): Unit = {
        /* Filter declared types from the candidates */
        filterCandidateThrowies(throwTypes)

        /* Move all remaining candidates to the list of unhandled throwables */
        validationErrorList ++= candidateThrowies.map { t => UnhandledThrowable(t.pos, t.tpe) }
        candidateThrowies = List()
      }

      /**
       * Return a snapshot of the currently collected set of validation errors. The errors will be ordered
       * according to the original point in the tree in which they occurred.
       */
      def validationErrors: Seq[ValidationError] = validationErrorList.toSeq
    }

    /** @inheritdoc */
    override def traverse (tree: Tree): Unit = {
      /* Traverse children; we work from the bottom up. */
      def defaultTraverse (): Unit = {
        super.traverseTrees(tree.children)
      }

      /* Look for exception-related constructs */
      tree match {
        /* Class body and constructors. This is a propagation point. */
        case cls:ClassDef =>
          /*
           * Find the primary constructor declaration.
           *
           * Primary constructor annotations are attached to the constructor method, but the constructor's code is
           * actually found in the class' body. We have to collect exception types from this constructor, and *then* descend
           * into the class body.
           */
          val primary = cls.impl.tpe.declarations.collectFirst {
            case m: MethodSymbol if m.isPrimaryConstructor => m
          }.get


          /* Find @throws annotations; we can't yet declare the propagation point, as the primary constructor's body is not
           * actually within the method; we have to traverse into the class first. */
          val throws: Seq[Type] = extractAnnotatedThrows(cls, primary.annotations) match {
            case Right(exceptions) => exceptions
            case Left(err) =>
              /* Report the error, return an empty set */
              mutableState.declareValidationError(err)
              Seq()
          }

          /* Traverse into the class to populate the set of candidate throwables. */
          defaultTraverse()

          /* Declare the propagation point. This uses the annotations found on the primary constructor, and the throwables
           * found within the class body itself. */
          mutableState.declarePropagationPoint(throws.toSet)

        /* try statement. This is a catch point. */
        case Try(block, catches, finalizer) =>
          /* Traverse into the try body to find all throwables */
          traverse(block)

          /*
           * Extract the exception types of all viable catches. We must exclude the following (usually valid) matches
           * that rely on runtime pattern matching:
           *
           * - Case statements that define a guard.
           * - Unapply-based statements (eg, NonFatal(e))
           *
           * Since the matching is dynamic in those cases, we have no way to assert that the guard will match all
           * possible values at runtime.
           *
           * Medieval, isn't it? This perfectly illustrated how unchecked exceptions destroy type safety.
           */
          val caught = catches.filter(_.guard.isEmpty).map(_.pat).filter { pattern =>
            /* Recursively search for any function calls within the catch pattern. */
            val fcalls = for (fcall @ Apply(_, _) <- pattern) yield fcall

            /* Disallow patterns that contain unapply calls. */
            if (fcalls.size > 0) {
              false
            } else {
              true
            }
          }.map(_.tpe)

          /* Declare the catch point */
          mutableState.declareCatchPoint(caught.toSet)

          /*
           * Now extract any throwables from subtrees that should *not* be covered by the try's catch() block. This
           * must be done in the same order as they're declared in code so that we report issues in the correct
           * order:
           *
           * - Guard blocks.
           * - Case statement bodies.
           * - Finalizer block
           */
          catches.foreach { c =>
            traverse(c.pat)
            traverse(c.guard)
            traverse(c.body)
          }
          traverse(finalizer)

        /* Method or constructor definition. This is a propagation point. */
        case defdef:DefDef =>
          /* Traverse all children */
          defaultTraverse()

          /* Find @throws annotations; declare the propagation point */
          extractAnnotatedThrows(defdef, defdef.symbol.annotations) match {
            case Right(types) =>
              mutableState.declarePropagationPoint(types.toSet)
            case Left(err) =>
              mutableState.declareValidationError(err)
          }


        /* Explicit throw */
        case thr:Throw =>
          /* Traverse all children */
          defaultTraverse()

          /* Add the type to the set of candidate throwables. */
          mutableState.declareCandidateThrowies(Seq(Throwie(thr.pos, thr.expr.tpe)))

        /*
         * Method/function call
         */
        case apply:Apply =>
          /* Traverse all children */
          defaultTraverse()

          /* Find exception types declared to be thrown by the target; declare them as candidate throwables */
          extractAnnotatedThrows(apply, apply.symbol.annotations) match {
            case Right(exceptions) =>
              mutableState.declareCandidateThrowies(exceptions.map(tpe => Throwie(apply.pos, tpe)))
            case Left(err) =>
              mutableState.declareValidationError(err)
          }

        case _ =>
          /* Hand off to default traversal method */
          super.traverse(tree)
      }
    }


    /**
     * Given a sequence of annotations, extract the exception type from any @throws annotations.
     *
     * @param owner The tree element to which the annotations are attached.
     * @param annotations The annotations from which to fetch @throws exception declarations.
     * @return The sequence of throwable types declared using @throws annotations.
     */
    private def extractAnnotatedThrows (owner: Tree, annotations: Seq[Annotation]): Either[ValidationError, Seq[Type]] = {
      /* Filter non-@throws annotations */
      val throwsAnnotations = annotations.filterNot(_.tpe =:= typeOf[throws[_]])

      /* Perform the actual extraction (recursively) */
      @tailrec def extractor (head: Annotation, tail: Seq[Annotation], accum: Seq[Type]): Either[ValidationError, Seq[Type]] = {
        /* Parse this annotation's argument. */
        val parsed = head match {
          /* Scala 2.9 API: @throws(classOf[Exception]) (which is throws[T](classOf[Exception])) */
          case Annotation(_, List(Literal(Constant(tpe: Type))), _) => Right(tpe)
            
          /* Scala 2.10 API: @throws[Exception], @throws[Exception]("cause") */
          case Annotation(TypeRef(_, _, args), _, _) => Right(args.head)
            
          /* Unsupported annotation arguments. */
          case _ => Left(InvalidThrowsAnnotation(owner.pos, s"Unsupported @throws annotation parameters on annotation `$head`"))
        }
        
        /* On success, recursively parse the next annotation. On failure, return immediately */
        parsed match {
          /* Extraction succeeded */
          case Right(tpe) =>
            if (tail.size == 0) {
              Right(accum :+ tpe)
            } else {
              extractor(tail.head, tail.drop(1), accum :+ tpe)
            }
          case Left(error) => Left(error)
        }
      }

      /* If there are any annotations, extract them */
      if (throwsAnnotations.size > 0)
        extractor(throwsAnnotations.head, throwsAnnotations.drop(1), Vector())
      else
        Right(Vector())
    }
  }
}
