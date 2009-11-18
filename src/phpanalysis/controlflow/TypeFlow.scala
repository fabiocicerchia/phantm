package phpanalysis.controlflow

import CFGTrees._
import scala.collection.immutable.HashMap
import scala.collection.immutable.HashSet
import scala.collection.immutable.Map
import Types._

object TypeFlow {
    case object TypeLattice extends Lattice {
        import Types._
        type E = Type

        def leq(x : Type, y : Type) = (x,y) match {
            case (TNone, _) => true
            case (_, TAny) => true
            case (x, y) if x == y => true
            case (t1: TPreciseArray, TAnyArray) => true
            case (t1: TUnion, t2: TUnion) =>
                (HashSet[Type]() ++ t1.types) subsetOf (HashSet[Type]() ++ t2.types)
            case _ => false
        }

        val top = TAny
        val bottom = TNone

        def join(x : Type, y : Type) = {
            val res = (x,y) match {
            case (TNone, _) => y
            case (_, TNone) => x
            case (TAnyArray, t: TPreciseArray) => TAnyArray
            case (t: TPreciseArray, TAnyArray) => TAnyArray
            case (t1: TPreciseArray, t2: TPreciseArray) => t1 merge t2
            case (t1, t2) if t1 == t2 => t1
            case (t1, t2) => TUnion(t1, t2)
        }
            //println("Joining "+x+" and "+y+", result: "+res)
            res
        }

        // unused
        def meet(x : Type, y : Type) = x
    }

    case class TypeEnvironment(map: Map[CFGSimpleVariable, Type]) extends Environment[TypeEnvironment] {
        def this() = {
            this(new HashMap[CFGSimpleVariable, Type]);
        }

        def lookup(v: CFGSimpleVariable): Option[Type] = map.get(v)

        def inject(v: CFGSimpleVariable, typ: Type): TypeEnvironment = {
            TypeEnvironment(map + ((v, typ)))
        }

        def union(e: TypeEnvironment) = {
            val newmap = new scala.collection.mutable.HashMap[CFGSimpleVariable, Type]();
            for ((v,t) <- map) {
                newmap(v) = t
            }
            //println("Union of "+this+" and "+e+"...")
            for ((v,t) <- e.map) {
                if (newmap contains v) {
             //       println("colliding vars!:"+v)
                    newmap(v) = TypeLattice.join(newmap(v), t)
                } else {
                    newmap(v) = t
                }
            }
            val res = new TypeEnvironment(Map[CFGSimpleVariable, Type]()++newmap)
            //println("Result: "+res);
            res
        }

        def equals(e: TypeEnvironment): Boolean = {
            if (e.map.size != map.size) return false
            for ((v,t) <- map) {
                if (!(e.map contains v)) return false
                else if (!(e.map(v) equals t)) return false
            }

            return true
        }

        override def toString = {
            map.map(x => x._1+" => "+x._2).mkString("[ ", "; ", " ]");
        }
    }

    case class TypeTransferFunction(silent: Boolean) extends TransferFunction[TypeEnvironment, CFGStatement] {
        def notice(msg: String, pos: Positional) = if (!silent) Reporter.notice(msg, pos)

        def apply(node : CFGStatement, env : TypeEnvironment) : TypeEnvironment = {
            def typeFromSimpleValue(sv: CFGSimpleValue): Type = sv match {
                case CFGNumLit(value) => TInt
                case CFGStringLit(value) => TString
                case CFGTrue() => TBoolean
                case CFGFalse() => TBoolean
                case CFGNull() => TNull
                case CFGThis() => TAnyObject
                case CFGEmptyArray() => new TPreciseArray()
                case CFGInstanceof(lhs, cl) => TBoolean
                case CFGArrayNext(ar) => typeFromSimpleValue(ar)
                case CFGArrayCurElement(id: CFGSimpleVariable) =>
                    env.lookup(id) match {
                        case Some(TAnyArray) =>
                            TAny
                        case Some(t: TPreciseArray) =>
                            t.entries.values.reduceLeft(TypeLattice.join)
                        case _ =>
                            TAny
                    }
                case CFGArrayCurElement(ar) => TAny
                case CFGArrayCurKey(ar) => TUnion(TString, TInt)
                case CFGArrayCurIsValid(ar) => expect(ar, TAnyArray); TBoolean
                case CFGNew(tpe, params) => TAnyObject
                case id: CFGSimpleVariable =>
                  env.lookup(id) match {
                      case Some(t) => t
                      case None => TAny
                  }
                case _ =>
                  TAny
            }

            def expect(v1: CFGSimpleValue, typs: Type*): Type = {
                val vtyp = typeFromSimpleValue(v1);
                for (t <- typs) {
                    if (TypeLattice.leq(vtyp, t)) {
                        return vtyp
                    }
                }
                notice("Potential type mismatch: expected: "+typs.toList.map{x => x.toText}.mkString(" or ")+", found: "+vtyp.toText, v1)
                //notice("Potential type mismatch: expected: "+typs.toList.mkString(" or ")+", found: "+vtyp, v1)
                typs.toList.head
            }

            def typeCheckBinOP(v1: CFGSimpleValue, op: CFGBinaryOperator, v2: CFGSimpleValue): Type = {
                op match {
                    case PLUS =>
                        expect(v1, TInt); expect(v2, TInt)
                    case MINUS =>
                        expect(v1, TInt); expect(v2, TInt)
                    case MULT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case DIV =>
                        expect(v1, TInt); expect(v2, TInt)
                    case CONCAT =>
                        expect(v1, TString); expect(v2, TString)
                    case MOD =>
                        expect(v1, TInt); expect(v2, TInt)
                    case INSTANCEOF =>
                        expect(v1, TAnyObject); expect(v2, TString); TBoolean
                    case BOOLEANAND =>
                        expect(v1, TBoolean); expect(v2, TBoolean)
                    case BOOLEANOR =>
                        expect(v1, TBoolean); expect(v2, TBoolean)
                    case BOOLEANXOR =>
                        expect(v1, TBoolean); expect(v2, TBoolean)
                    case BITWISEAND =>
                        expect(v1, TInt); expect(v2, TInt)
                    case BITWISEOR =>
                        expect(v1, TInt); expect(v2, TInt)
                    case BITWISEXOR =>
                        expect(v1, TInt); expect(v2, TInt)
                    case SHIFTLEFT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case SHIFTRIGHT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case LT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case LEQ =>
                        expect(v1, TInt); expect(v2, TInt)
                    case GEQ =>
                        expect(v1, TInt); expect(v2, TInt)
                    case GT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case EQUALS =>
                        expect(v2, expect(v1, TAny)); TBoolean
                    case IDENTICAL =>
                        expect(v2, expect(v1, TAny)); TBoolean
                    case NOTEQUALS =>
                        expect(v2, expect(v1, TAny)); TBoolean
                    case NOTIDENTICAL =>
                        expect(v2, expect(v1, TAny)); TBoolean
                    case OBJECTREAD =>
                        expect(v1, TAnyObject); TAny
                    case ARRAYREAD => 
                        val r = (expect(v1, TAnyArray, TString), v2) match {
                            case (TAnyArray, _) =>
                                TAny
                            case (a: TPreciseArray, CFGNumLit(i)) =>
                                a.lookup(i+"") match {
                                    case Some(t) =>
                                        t
                                    case None =>
                                        notice("Undefined array index '"+i+"'", v1)
                                        TNull
                                }
                            case (a: TPreciseArray, CFGStringLit(s)) =>
                                a.lookup(s) match {
                                    case Some(t) =>
                                        t
                                    case None =>
                                        notice("Undefined array index '"+s+"'", v1)
                                        TNull
                                }
                            case (a: TPreciseArray, _) =>
                                // union of every types + default type
                                var t = a.entries map { _._2 } reduceLeft { (x,y) => x union y }
                                if (a.pollutedType != None) {
                                    t = t union a.pollutedType.get
                                }
                                t
                            case _ =>
                                TAny

                        }
                        r
                }
          }

          def complexAssign(v: CFGVariable, ex: CFGSimpleValue): TypeEnvironment = {
                var line: List[(CFGSimpleValue, Type, Type)] = Nil;
                //println("Got: "+v+" = "+ex);
                def linearize(sv: CFGSimpleValue, checkType: Type, resultType: Type, pass: Int): Unit = {
                    line = (sv, checkType, resultType) :: line;
                    sv match {
                        case CFGVariableVar(v) =>
                            linearize(v, TString, TString, pass+1)
                        case CFGArrayEntry(arr, index) =>
                            val rt = new TPreciseArray().inject(index, resultType);
                            val ct = if (pass > 0) new TPreciseArray().inject(index, checkType) else TAnyArray;
                            linearize(arr, ct, rt, pass+1)
                        case CFGNextArrayEntry(arr) =>
                            val rt = new TPreciseArray().injectNext(resultType, arr);
                            val ct = if (pass > 0) new TPreciseArray().injectNext(checkType, arr) else TAnyArray;
                            linearize(arr, ct, rt, pass+1)
                        case _ =>
                    }
                }

                val ext = typeFromSimpleValue(ex);
                linearize(v, ext, ext, 0)

                var e = env

                for ((elem, ct, rt) <- line.init) {
                    println("Checking "+elem+"["+typeFromSimpleValue(elem)+"] (check: "+ct+")(after: "+rt+")")

                    val resultingType = (rt, typeFromSimpleValue(elem)) match {
                        case (a: TArray, b: TArray) =>
                            // We need to merge those arrays in a special way
                            def assignMerge(from: Type, to: Type): Type = (from,to) match {
                                case (from: TPreciseArray, to: TPreciseArray) =>
                                    import scala.collection.mutable.HashMap
                                    import Math.max

                                    val newPollutedType = (from.pollutedType, to.pollutedType) match {
                                        case (Some(pt1), Some(pt2)) => Some(TypeLattice.join(pt1, pt2))
                                        case (Some(pt1), None) => Some(pt1)
                                        case (None, Some(pt2)) => Some(pt2)
                                        case (None, None) => None
                                    }

                                    val newEntries = HashMap[String, Type]() ++ from.entries;

                                    for((index, typ)<- to.entries) {
                                        newEntries(index) = newEntries.get(index) match {
                                            case Some(t) => assignMerge(t, typ)
                                            case None => typ
                                        }
                                    }

                                    new TPreciseArray(newEntries, newPollutedType, max(from.nextFreeIndex, to.nextFreeIndex))
                                case (a, b) => b
                            }
                            expect(elem, ct)
                            assignMerge(b, a)
                        case (a: TArray, b) =>
                            expect(elem, ct)
                            rt
                        case _ =>
                            println("Woooops?? What is that type?")
                            rt
                    }

                    println("  Setting to: "+resultingType)

                    elem match {
                        case sv: CFGSimpleVariable =>
                            e = e.inject(sv, resultingType);
                        case _ =>
                    }
                }

                e
          }


          node match {
              case CFGAssign(vr: CFGSimpleVariable, v1) =>
                val e = env.inject(vr, typeFromSimpleValue(v1));
                e
              case CFGAssignBinary(vr: CFGSimpleVariable, v1, op, v2) =>
                // We want to typecheck v1/v2 according to OP
                env.inject(vr, typeCheckBinOP(v1, op, v2));
              case CFGAssignBinary(_, v1, op, v2) =>
                // We want to typecheck v1/v2 according to OP
                typeCheckBinOP(v1, op, v2); env // todo: pollute env

              case CFGAssign(ca: CFGVariable, ex) =>
                complexAssign(ca, ex)

              /*
              case CFGAssign(CFGObjectProperty(obj, prop), ex) =>
                //expect(obj, TAnyObject);
                env

              case CFGAssign(CFGNextArrayEntry(arr), expr) =>
                arr match {
                    case id: CFGSimpleVariable => 
                        val t = env.lookup(id) match {
                          case Some(t: TArray) => t
                          case Some(_) => expect(arr, TAnyArray); new TPreciseArray()
                          case None => new TPreciseArray()
                        }
                        t.injectNext(typeFromSimpleValue(expr), expr)
                        env.inject(id, t)

                    case _ => println("simple identified expeceted!!"); env
                }

              case CFGAssign(CFGArrayEntry(arr, index), expr) =>
                arr match {
                    case id: CFGSimpleVariable =>
                        val t = env.lookup(id) match {
                          case Some(t: TArray) => t
                          case Some(_) => expect(arr, TAnyArray); new TPreciseArray()
                          case None => new TPreciseArray()
                        }

                        val exprTyp = typeFromSimpleValue(expr)

                        index match {
                          case CFGNumLit(i)        => t.inject(i+"", exprTyp)
                          case CFGStringLit(index) => t.inject(index, exprTyp)
                          case _ =>
                            expect(index, TInt, TString)
                            // we pollute
                            t.pollute(exprTyp)
                        }
                        env.inject(id, t)

                    case _ => println("simple identified expeceted!!"); env
                }
                */
              case CFGAssignMethodCall(v, r, mid, p) =>
                expect(r, TAnyObject); env

              case CFGAssume(v1, op, v2) => op match {
                  case LT | LEQ | GEQ | GT =>
                    expect(v1, TInt); expect(v2, TInt); env
                  case EQUALS | IDENTICAL | NOTEQUALS | NOTIDENTICAL =>
                    expect(v2, expect(v1, TAny)); env

              }

              case CFGPrint(v) => env
              case CFGUnset(id: CFGSimpleVariable) => env.inject(id, TNull); env

              case _ => println(node+" not yet handled"); env
          }
      }
    }

    case class Analyzer(cfg: CFG) {


        def analyze = {
            val baseEnv = new TypeEnvironment;
            val aa = new AnalysisAlgorithm[TypeEnvironment, CFGStatement](TypeTransferFunction(true), baseEnv, cfg)

            aa.init
            aa.computeFixpoint

            //*
            for ((v,e) <- aa.getResult.toList.sort{(x,y) => x._1.name < y._1.name}) {
                println("node "+v+" has env "+e);
            }
            // */

            aa.pass(TypeTransferFunction(false))
        }
    }
}