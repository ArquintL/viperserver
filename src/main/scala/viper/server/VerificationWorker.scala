/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.server


import java.nio.file.Paths

import scala.collection.mutable.ListBuffer

import akka.actor.ActorRef

import com.typesafe.scalalogging.LazyLogging

import viper.carbon.CarbonFrontend
import viper.server.ViperServerRunner.ReporterActor
import viper.silicon.SiliconFrontend
import viper.silver.ast._
import viper.silver.frontend.{SilFrontend, TranslatorState}
import viper.silver.reporter
import viper.silver.reporter.Reporter
import viper.silver.verifier.errors._
import viper.silver.verifier.{AbstractVerificationError, _}




// Implementation of the Reporter interface used by the backend.
class ActorReporter(private val actor_ref: ActorRef, val tag: String) extends viper.silver.reporter.Reporter {

  val name = s"ViperServer_$tag"

  def report(msg: reporter.Message) = {
    //println(s">>> ActorReporter reporting: " + msg.toString)
    actor_ref ! ReporterActor.ServerRequest(msg)
  }
}

class VerificationWorker(val _reporter: ActorRef, val command: List[String]) extends Runnable with LazyLogging {
  private var _frontend: ViperFrontend = null

  def run(): Unit = {
    try {
      command match {
        case "silicon" :: args =>
          startVerification(args, new ViperSiliconFrontend(new ActorReporter(_reporter, "silicon")))
        case "carbon" :: args =>
          startVerification(args, new ViperCarbonFrontend(new ActorReporter(_reporter, "carbon")))
        case args =>
          logger.info("invalid arguments: ${args.toString}",
                      "You need to specify the verification backend, e.g., `silicon [args]`")
      }
    } catch {
      case _: InterruptedException =>
      case _: java.nio.channels.ClosedByInterruptException =>
      case e: Exception =>
        e.printStackTrace(System.err)
    } finally {
      stop()
      if (_frontend != null) {
        _frontend.printStopped()
      } else {
        println(s"The command $command did not result in initialization of verification backend.")
      }
      _reporter ! ReporterActor.FinalServerRequest
    }
  }

  private def startVerification(args: List[String], frontend: ViperFrontend): Unit = {
    //frontend.setSender(sender)
    _frontend = frontend
    frontend.execute(args)
  }

  private def stop(): Unit = {
    try {
      _frontend.verifier.stop()
    } catch {
      case _: Throwable =>
    }
  }
}

trait ViperFrontend extends SilFrontend {
  //protected var _sender: ActorRef = null

  //def setSender(sender: ActorRef): Unit = {
  //  _sender = sender
  //}

  def ideMode: Boolean = config != null && config.ideMode()

  def printStopped(): Unit = {
    if (ideMode) {
      loggerForIde.info(s"""{"type":"Stopped"}\r\n""")
    } else {
      logger.info(s"${_ver.name} stopped")
    }
  }

  override def execute(args: Seq[String]) {
    setStartTime()

    /* Create the verifier */
    _ver = createVerifier(args.mkString(" "))

    //_sender ! Backend(_ver)

    if (!prepare(args)) return

    // initialize the translator
    init(_ver)

    // set the file we want to verify
    reset(Paths.get(config.file()))

    // run the parser, typechecker, and verifier
    parse()
    typecheck()
    translate()

    if (_errors.nonEmpty) {
      _state = TranslatorState.Verified
    } else {
      printOutline(_program.get)
      printDefinitions(_program.get)
      if (config.disableCaching()) {
        doVerify()
      } else {
        println("start cached verification")
        doVerifyCached()
      }
    }

    _ver.stop()

    finish()
  }

  private def getMethodSpecificErrors(m: Method, errors: Seq[AbstractError]): List[AbstractVerificationError] = {
    //The position of the error is used to determine to which Method it belongs.
    val methodStart = m.pos.asInstanceOf[SourcePosition].start.line
    val methodEnd = m.pos.asInstanceOf[SourcePosition].end.get.line
    val result = scala.collection.mutable.ListBuffer[AbstractVerificationError]()

    errors.foreach {
      case e: AbstractVerificationError =>
        e.pos match {
          case pos: HasLineColumn =>
            val errorPos = pos.line
            if (errorPos >= methodStart && errorPos <= methodEnd) result += e
          case _ =>
            throw new Exception("Error determining method specific errors for the cache: The reported errors should have a location")
        }
      case e =>
        throw new Exception("Error with unexpected type found: " + e)
    }
    result.toList
  }

  private def removeBody(m: Method): Method = {
    val node: Seqn = Seqn(Seq(Inhale(FalseLit()())()), m.scopedDecls)(m.body.pos, m.body.info, m.body.errT)
    m.copy(body = node)(m.pos, m.info, m.errT)
  }

  def doVerifyCached(): Unit = {

    // The entityHashes of the new AST are evaluated lazily.

    val (methodsToVerify, _, cachedErrors) = consultCache()

    val real_program = _program.get
    val program = Program(real_program.domains, real_program.fields, real_program.functions, real_program.predicates,
      methodsToVerify) (real_program.pos, real_program.info, real_program.errT)
    val file: String = _config.file()

    _verificationResult = Some(mapVerificationResult(_verifier.get.verify(program)))
    assert(_verificationResult != null)

    _state = TranslatorState.Verified

    //update cache
    methodsToVerify.foreach(m => {
      _verificationResult.get match {
        case Failure(errors) =>
          val errorsToCache = getMethodSpecificErrors(m, errors)
          ViperCache.update(backendName, file, program, m, errorsToCache)
          logger.trace("Store in cache " + m.name + (if (errorsToCache.nonEmpty) ": Error" else ": Success"))
        case Success =>
          logger.trace("Store in cache " + m.name + ": Success")
          ViperCache.update(backendName, file, program, m, Nil)
      }
    })

    //combine errors:
    if (cachedErrors.nonEmpty) {
      _verificationResult.get match {
        case Failure(errorList) =>
          _verificationResult = Some(Failure(errorList ++ cachedErrors))
        case Success =>
          _verificationResult = Some(Failure(cachedErrors))
      }
    }
  }

  def backendName: String = _ver.getClass.getName

  def consultCache(): (List[Method], List[Method], List[VerificationError]) = {
    val errors: collection.mutable.ListBuffer[VerificationError] = ListBuffer()
    val methodsToVerify: collection.mutable.ListBuffer[Method] = ListBuffer()
    val methodsToCache: collection.mutable.ListBuffer[Method] = ListBuffer()

    val file: String = _config.file()
    val program = _program.get

    //read errors from cache
    program.methods.foreach((m: Method) => {
      ViperCache.get(backendName, file, m) match {
        case None =>
          methodsToVerify += m
        case Some(cacheEntry) =>
          if (program.dependencyHashMap(m) != cacheEntry.dependencyHash) {
            //even if the method itself did not change, a re-verification is required if it's dependencies changed
            methodsToVerify += m
          } else {
            try {
              val cachedErrors = updateErrorLocation(m, cacheEntry)
              errors ++= cachedErrors
              methodsToCache += removeBody(m)
            } catch {
              case e: Exception =>
                logger.warn("The cache lookup failed:" + e)
                //Default to verifying the method in case the cache lookup fails.
                methodsToVerify += m
            }
          }
      }
    })
    (methodsToVerify.toList, methodsToCache.toList, errors.toList)
  }

  private def updateErrorLocation(m: Method, cacheEntry: CacheEntry): List[VerificationError] = {
    cacheEntry.errors.map(updateErrorLocation(m, _))
  }

  private def updateErrorLocation(m: Method, error: LocalizedError): VerificationError = {
    assert(error.error != null && error.accessPath != null && error.reasonAccessPath != null)

    //get the corresponding offending node in the new AST
    //TODO: are these casts ok?
    val offendingNode = ViperCache.getNode(m, error.accessPath, error.error.offendingNode).asInstanceOf[Option[errors.ErrorNode]]
    val reasonOffendingNode = ViperCache.getNode(m, error.reasonAccessPath, error.error.reason.offendingNode).asInstanceOf[Option[errors.ErrorNode]]

    if (offendingNode.isEmpty || reasonOffendingNode.isEmpty) {
      throw new Exception("Cache error: no corresponding node found for error: " + error.error.readableMessage())
    }

    //create a new VerificationError that only differs in the Position of the offending Node
    //the cast is fine, because the offending Nodes are supposed to be ErrorNodes
    val updatedOffendingNode = updatePosition(error.error.offendingNode, offendingNode.get.pos).asInstanceOf[errors.ErrorNode]
    val updatedReasonOffendingNode = updatePosition(error.error.reason.offendingNode, reasonOffendingNode.get.pos).asInstanceOf[errors.ErrorNode]
    //TODO: how to also update the position of error.error.reason.offendingNode?
    val updatedError = error.error.withNode(updatedOffendingNode).asInstanceOf[AbstractVerificationError]
    setCached(updatedError)
  }

  def setCached(error: AbstractVerificationError): AbstractVerificationError = {
    error match {
      case e: Internal => e.copy(cached = true)
      case e: AssignmentFailed => e.copy(cached = true)
      case e: CallFailed => e.copy(cached = true)
      case e: ContractNotWellformed => e.copy(cached = true)
      case e: PreconditionInCallFalse => e.copy(cached = true)
      case e: PreconditionInAppFalse => e.copy(cached = true)
      case e: ExhaleFailed => e.copy(cached = true)
      case e: InhaleFailed => e.copy(cached = true)
      case e: IfFailed => e.copy(cached = true)
      case e: WhileFailed => e.copy(cached = true)
      case e: AssertFailed => e.copy(cached = true)
      case e: TerminationFailed => e.copy(cached = true)
      case e: PostconditionViolated => e.copy(cached = true)
      case e: FoldFailed => e.copy(cached = true)
      case e: UnfoldFailed => e.copy(cached = true)
      case e: PackageFailed => e.copy(cached = true)
      case e: ApplyFailed => e.copy(cached = true)
      case e: LoopInvariantNotPreserved => e.copy(cached = true)
      case e: LoopInvariantNotEstablished => e.copy(cached = true)
      case e: FunctionNotWellformed => e.copy(cached = true)
      case e: PredicateNotWellformed => e.copy(cached = true)
      case e: MagicWandNotWellformed => e.copy(cached = true)
      case e: LetWandFailed => e.copy(cached = true)
      case e: HeuristicsFailed => e.copy(cached = true)
      case e: VerificationErrorWithCounterexample => e.copy(cached = true)
      case e: AbstractVerificationError =>
        logger.warn("Setting a verification error to cached was not possible for " + e + ". Make sure to handle this types of errors")
        e
    }
  }

  def updatePosition(n: Node, pos: Position): Node = {
    n match {
      case t: Trigger => t.copy()(pos, t.info, t.errT)
      case t: Program => t.copy()(pos, t.info, t.errT)

      //Members
      case t: Field => t.copy()(pos, t.info, t.errT)
      case t: Function => t.copy()(pos, t.info, t.errT)
      case t: Method => t.copy()(pos, t.info, t.errT)
      case t: Predicate => t.copy()(pos, t.info, t.errT)
      case t: Domain => t.copy()(pos, t.info, t.errT)

      //DomainMembers
      case t: DomainAxiom => t.copy()(pos, t.info, t.domainName, t.errT)
      case t: DomainFunc => t.copy()(pos, t.info, t.domainName, t.errT)

      //Statements
      case t: NewStmt => t.copy()(pos, t.info, t.errT)
      case t: LocalVarAssign => t.copy()(pos, t.info, t.errT)
      case t: FieldAssign => t.copy()(pos, t.info, t.errT)
      case t: Fold => t.copy()(pos, t.info, t.errT)
      case t: Unfold => t.copy()(pos, t.info, t.errT)
      case t: Package => t.copy()(pos, t.info, t.errT)
      case t: Apply => t.copy()(pos, t.info, t.errT)
      case t: Inhale => t.copy()(pos, t.info, t.errT)
      case t: Exhale => t.copy()(pos, t.info, t.errT)
      case t: Assert => t.copy()(pos, t.info, t.errT)
      case t: MethodCall => t.copy()(pos, t.info, t.errT)
      case t: Seqn => t.copy()(pos, t.info, t.errT)
      case t: While => t.copy()(pos, t.info, t.errT)
      case t: If => t.copy()(pos, t.info, t.errT)
      case t: Label => t.copy()(pos, t.info, t.errT)
      case t: Goto => t.copy()(pos, t.info, t.errT)
      case t: Fresh => t.copy()(pos, t.info, t.errT)
      case t: Constraining => t.copy()(pos, t.info, t.errT)
      case t: LocalVarDeclStmt => t.copy()(pos, t.info, t.errT)

      case t: LocalVarDecl => t.copy()(pos, t.info, t.errT)

      //Expressions
      case t: FalseLit => t.copy()(pos, t.info, t.errT)
      case t: NullLit => t.copy()(pos, t.info, t.errT)
      case t: TrueLit => t.copy()(pos, t.info, t.errT)
      case t: IntLit => t.copy()(pos, t.info, t.errT)
      case t: LocalVar => t.copy()(t.typ, pos, t.info, t.errT)
      case t: viper.silver.ast.Result => t.copy()(t.typ, pos, t.info, t.errT)
      case t: FieldAccess => t.copy()(pos, t.info, t.errT)
      case t: PredicateAccess => t.copy()(pos, t.info, t.errT)
      case t: Unfolding => t.copy()(pos, t.info, t.errT)
      case t: Applying => t.copy()(pos, t.info, t.errT)
      case t: CondExp => t.copy()(pos, t.info, t.errT)
      case t: Let => t.copy()(pos, t.info, t.errT)
      case t: Exists => t.copy()(pos, t.info, t.errT)
      case t: Forall => t.copy()(pos, t.info, t.errT)
      case t: ForPerm => t.copy()(pos, t.info, t.errT)
      case t: InhaleExhaleExp => t.copy()(pos, t.info, t.errT)
      case t: WildcardPerm => t.copy()(pos, t.info, t.errT)
      case t: FullPerm => t.copy()(pos, t.info, t.errT)
      case t: NoPerm => t.copy()(pos, t.info, t.errT)
      case t: EpsilonPerm => t.copy()(pos, t.info, t.errT)
      case t: CurrentPerm => t.copy()(pos, t.info, t.errT)
      case t: FieldAccessPredicate => t.copy()(pos, t.info, t.errT)
      case t: PredicateAccessPredicate => t.copy()(pos, t.info, t.errT)

      //Binary operators
      case t: Add => t.copy()(pos, t.info, t.errT)
      case t: Sub => t.copy()(pos, t.info, t.errT)
      case t: Mul => t.copy()(pos, t.info, t.errT)
      case t: Div => t.copy()(pos, t.info, t.errT)
      case t: Mod => t.copy()(pos, t.info, t.errT)
      case t: LtCmp => t.copy()(pos, t.info, t.errT)
      case t: LeCmp => t.copy()(pos, t.info, t.errT)
      case t: GtCmp => t.copy()(pos, t.info, t.errT)
      case t: GeCmp => t.copy()(pos, t.info, t.errT)
      case t: EqCmp => t.copy()(pos, t.info, t.errT)
      case t: NeCmp => t.copy()(pos, t.info, t.errT)
      case t: Or => t.copy()(pos, t.info, t.errT)
      case t: And => t.copy()(pos, t.info, t.errT)
      case t: Implies => t.copy()(pos, t.info, t.errT)
      case t: MagicWand => t.copy()(pos, t.info, t.errT)
      case t: FractionalPerm => t.copy()(pos, t.info, t.errT)
      case t: PermDiv => t.copy()(pos, t.info, t.errT)
      case t: PermAdd => t.copy()(pos, t.info, t.errT)
      case t: PermSub => t.copy()(pos, t.info, t.errT)
      case t: PermMul => t.copy()(pos, t.info, t.errT)
      case t: IntPermMul => t.copy()(pos, t.info, t.errT)
      case t: PermLtCmp => t.copy()(pos, t.info, t.errT)
      case t: PermLeCmp => t.copy()(pos, t.info, t.errT)
      case t: PermGtCmp => t.copy()(pos, t.info, t.errT)
      case t: PermGeCmp => t.copy()(pos, t.info, t.errT)
      case t: AnySetUnion => t.copy()(pos, t.info, t.errT)
      case t: AnySetIntersection => t.copy()(pos, t.info, t.errT)
      case t: AnySetSubset => t.copy()(pos, t.info, t.errT)
      case t: AnySetMinus => t.copy()(pos, t.info, t.errT)
      case t: AnySetContains => t.copy()(pos, t.info, t.errT)

      //Unary operators
      case t: Minus => t.copy()(pos, t.info, t.errT)
      case t: Not => t.copy()(pos, t.info, t.errT)
      case t: PermMinus => t.copy()(pos, t.info, t.errT)
      case t: Old => t.copy()(pos, t.info, t.errT)
      case t: LabelledOld => t.copy()(pos, t.info, t.errT)
      case t: AnySetCardinality => t.copy()(pos, t.info, t.errT)
      case t: FuncApp => t.copy()(pos, t.info, t.typ, t.formalArgs, t.errT)
      case t: DomainFuncApp => t.copy()(pos, t.info, t.typ, t.formalArgs, t.domainName, t.errT)
      case t: EmptySeq => t.copy()(pos, t.info, t.errT)
      case t: ExplicitSeq => t.copy()(pos, t.info, t.errT)
      case t: RangeSeq => t.copy()(pos, t.info, t.errT)
      case t: SeqAppend => t.copy()(pos, t.info, t.errT)
      case t: SeqIndex => t.copy()(pos, t.info, t.errT)
      case t: SeqTake => t.copy()(pos, t.info, t.errT)
      case t: SeqDrop => t.copy()(pos, t.info, t.errT)
      case t: SeqContains => t.copy()(pos, t.info, t.errT)
      case t: SeqUpdate => t.copy()(pos, t.info, t.errT)
      case t: SeqLength => t.copy()(pos, t.info, t.errT)

      //others
      case t: EmptySet => t.copy()(pos, t.info, t.errT)
      case t: ExplicitSet => t.copy()(pos, t.info, t.errT)
      case t: EmptyMultiset => t.copy()(pos, t.info, t.errT)
      case t: ExplicitMultiset => t.copy()(pos, t.info, t.errT)
      case t =>
        logger.warn("The location was not updated for the node " + t + ". Make sure to handle this type of node")
        t
    }
  }
}

class ViperCarbonFrontend(val rep: Reporter) extends CarbonFrontend(rep) with ViperFrontend {}

class ViperSiliconFrontend(val rep: Reporter) extends SiliconFrontend(rep) with ViperFrontend {}