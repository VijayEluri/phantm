package phpanalysis.controlflow

import CFGTrees._
import scala.collection.immutable.HashMap
import scala.collection.immutable.HashSet
import scala.collection.immutable.Map
import analyzer.Symbols._
import analyzer.Types._

object TypeFlow {
    case object TypeLattice extends Lattice[TypeEnvironment] {
        type E = Type

        def leq(x : Type, y : Type): Boolean = (x,y) match {
            case (x, y) if x == y => true

            case (TBottom, _) => true
            case (_, TTop) => true
            case (_:ConcreteType, TAny) => true
            case (TTrue, TBoolean) => true
            case (TFalse, TBoolean) => true
            case (t1: TObjectRef, TAnyObject) => true
            case (t1: TObjectRef, t2: TObjectRef) =>
                if (t1.id == t2.id) {
                    true
                } else {
                    val r1 = t1.realObject
                    val r2 = t2.realObject

                    val classesMatch = (r1, r2) match {
                        case (r1: TRealClassObject, r2: TRealClassObject) =>
                            r1.cl isSubtypeOf r2.cl
                        case (r1: TRealObject, r2: TRealClassObject) =>
                            false
                        case _ =>
                            true
                    }

                    classesMatch && (r1.globalType leq r2.globalType) && ((r1.fields.keySet ++ r2.fields.keySet) forall (k =>
                        (r1.lookupField(k) leq r1.lookupField(k))))
                }

            case (t1: TArray, t2: TArray) =>
                (t1.globalType leq t2.globalType) && ((t1.entries.keySet ++ t2.entries.keySet) forall (k =>
                    (t1.lookup(k) leq t2.lookup(k))))

            case (t1: TUnion, t2: TUnion) =>
                t1.types forall { x => t2.types.exists { y => x leq y } }
            case (t1, t2: TUnion) =>
                t2.types exists { x => t1 leq x }
            case (t1: TUnion, t2) =>
                t1.types forall { x => x leq t2 }
            case _ => false
        }

        val top = TTop
        val bottom = TBottom

        def join(x: Type, y: Type): Type = (x,y) match {
            case (TTop, _) => TTop
            case (_, TTop) => TTop

            case (TBottom, _) => y
            case (_, TBottom) => x

            case (TAny, TUninitialized) => TTop
            case (TUninitialized, TAny) => TTop

            case (TAny, _: ConcreteType) => TAny
            case (_: ConcreteType, TAny) => TAny

            case (TAny, tu: TUnion) =>
                if (!(tu.types contains TUninitialized)) {
                    TAny
                } else {
                    TTop
                }
            case (tu: TUnion, TAny) =>
                if (!(tu.types contains TUninitialized)) {
                    TAny
                } else {
                    TTop
                }

            case (TTrue, TFalse) => TBoolean
            case (TFalse, TTrue) => TBoolean
            case (TTrue, TBoolean) => TBoolean
            case (TBoolean, TTrue) => TBoolean
            case (TFalse, TBoolean) => TBoolean
            case (TBoolean, TFalse) => TBoolean

            case (t1, t2) if t1 == t2 => t1

            // Objects
            case (TAnyObject, t: TObjectRef) => TAnyObject
            case (t: TObjectRef, TAnyObject) => TAnyObject
            case (t1: TObjectRef, t2: TObjectRef) =>
                if (t1.id != t2.id) {
                    // Different ids -> union
                    TUnion(t1, t2)
                } else {
                    t1
                }

            // Arrays
            case (TAnyArray, t: TArray) => TAnyArray
            case (t: TArray, TAnyArray) => TAnyArray
            case (t1: TArray, t2: TArray) =>
                var newEntries = Map[String, Type]();

                for (k <- t1.entries.keySet ++ t2.entries.keySet) {
                    newEntries = newEntries.update(k, t1.lookup(k) union t2.lookup(k))
                }

                new TArray(newEntries, t1.globalType union t2.globalType)
            // Unions
            case (t1, t2) => TUnion(t1, t2)
        }

        // For meet we actually require the environment, since object types
        // will be updated in the store
        def meet(envInit: TypeEnvironment, x : Type, y : Type): (TypeEnvironment, Type) = {
            var env = envInit
            def meetTypes(x: Type, y: Type): Type = (x,y) match {
                case (TTop, _) => y
                case (_, TTop) => x

                case (TBottom, _) => TBottom
                case (_, TBottom) => TBottom

                case (TAny, _: ConcreteType) => y
                case (_: ConcreteType, TAny) => x

                case (TAny, tu: TUnion) =>
                    if (!(tu.types contains TUninitialized)) {
                        tu
                    } else {
                        TBottom
                    }
                case (tu: TUnion, TAny) =>
                    if (!(tu.types contains TUninitialized)) {
                        tu
                    } else {
                        TBottom
                    }

                case (t1, t2) if t1 == t2 => t1

                // Arrays
                case (t1: TArray, t2: TArray) =>
                    var newEntries = Map[String, Type]();

                    for (k <- t1.entries.keySet ++ t2.entries.keySet) {
                        newEntries = newEntries.update(k, meetTypes(t1.lookup(k), t2.lookup(k)))
                    }

                    new TArray(newEntries, meetTypes(t1.globalType, t2.globalType))


                // Unions
                case (tu1: TUnion, tu2: TUnion) =>
                    var resUnion = Set[Type]();

                    // we take the intersection
                    for (t1 <- tu1.types) {
                       if (t1 leq tu2) {
                           resUnion = resUnion + t1;
                       }
                    }
                    for (t2 <- tu2.types) {
                       if (t2 leq tu1) {
                           resUnion = resUnion + t2;
                       }
                    }

                    if (resUnion.size == 0) {
                        TBottom
                    } else if (resUnion.size == 1) {
                        resUnion.toList.head
                    } else {
                        TUnion(resUnion)
                    }

                // Arbitrary types
                case (t1, t2) =>
                    if (t1 leq t2) {
                        t1
                    } else if (t2 leq t1) {
                        t2
                    } else {
                        TBottom
                    }
            }

            (env, meetTypes(x,y))
        }
    }

    object BaseTypeEnvironment extends TypeEnvironment(HashMap[CFGSimpleVariable, Type](), None, new ObjectStore) {
        override def union(e: TypeEnvironment) = {
            e
        }

        override def equals(e: Any) = {
            if (e.isInstanceOf[AnyRef]) {
                BaseTypeEnvironment eq e.asInstanceOf[AnyRef]
            } else {
                false
            }
        }

        override def copy: TypeEnvironment =
            this

        override def toString = {
            "<base>"
        }

    }

    object AnnotationsStore {
        var functions = HashMap[String, (List[TFunction], Type)]();

        def collectFunctionRet(fs: FunctionSymbol, t: Type) = {
            val newData = functions.get(fs.name) match {
                case Some(data) =>
                    (data._1, t)
                case None =>
                    (Nil, t)
            }

            functions += (fs.name -> newData)

        }
        def collectFunction(fs: FunctionSymbol, ft: TFunction) = {
            val newData = functions.get(fs.name) match {
                case Some(data) =>
                    (ft :: data._1, data._2)
                case None =>
                    (ft :: Nil, TAny)
            }

            functions += (fs.name -> newData)
        }
    }

    class TypeEnvironment(val map: Map[CFGSimpleVariable, Type], val scope: Option[ClassSymbol], val store: ObjectStore) extends Environment[TypeEnvironment, CFGStatement] {
        def this(scope: Option[ClassSymbol]) = {
            this(new HashMap[CFGSimpleVariable, Type], scope, new ObjectStore);
        }

        def this() = {
            this(new HashMap[CFGSimpleVariable, Type], None, new ObjectStore);
        }

        def lookup(v: CFGSimpleVariable): Option[Type] = map.get(v)

        def inject(v: CFGSimpleVariable, typ: Type): TypeEnvironment =
            new TypeEnvironment(map + ((v, typ)), scope, store)

        def setStore(st: ObjectStore): TypeEnvironment = {
            new TypeEnvironment(map, scope, st)
        }

        def setObject(id: ObjectId, ot: TRealObject): TypeEnvironment = {
            new TypeEnvironment(map, scope, store.set(id, ot))
        }

        def initObjectIfNotExist(id: ObjectId, cl: Option[ClassSymbol]) = {
            new TypeEnvironment(map, scope, store.initIfNotExist(id, cl))
        }

        def copy: TypeEnvironment =
            new TypeEnvironment(Map[CFGSimpleVariable, Type]()++map, scope, store)

        def union(e: TypeEnvironment): TypeEnvironment = {
            e match {
                case BaseTypeEnvironment =>
                    this

                case te: TypeEnvironment =>
                    var newmap = Map[CFGSimpleVariable, Type]();

                    for (k <- map.keySet ++ e.map.keySet) {
                        newmap = newmap.update(k,
                            map.getOrElse(k, TTop) union e.map.getOrElse(k, TTop))
                    }

                    new TypeEnvironment(newmap, scope, te.store union store)
            }
        }

        def checkMonotonicity(vrtx: Vertex, e: TypeEnvironment, inEdges: Iterable[(CFGStatement, TypeEnvironment)]): Unit = {
            var delim = false;
            for ((v, t) <- map) {
                if (e.map contains v) {
                    if (!(t leq e.map(v))) {
                        if (!delim) {
                            println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@")
                            println("@@@@@@@@@@@@@@@@@ "+vrtx+" @@@@@@@@@@@@@@@@@@@")
                            delim = true;
                        }
                        println(" "+v+" => ")
                        println("      OLD: "+t)
                        println("      NEW: "+e.map(v))
                        println(" incoming values: ")
                        for ((cfg, e) <- inEdges) {
                            println("   * "+cfg+" => "+e.lookup(v))
                            println
                        }
                        println("@@@@@@@@@@@@@@@@@")
                    }
                } else {
                    if (!delim) {
                        println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@")
                        println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@")
                        delim = true;
                    }
                    println(" "+v+" is not in NEW ?")
                }
            }
        }

        override def equals(e: Any): Boolean = {
            e match {
                case BaseTypeEnvironment =>
                    false

                case env: TypeEnvironment =>
                    scope == env.scope && map == env.map && store == env.store
                case _ =>
                    false

            }
        }

        override def toString = {
            def typeToString(t: Type): String = t match {
                case or: TObjectRef =>
                    "(#"+or.id.pos+","+or.id.offset+")"+store.lookup(or).toString
                case _ => t.toString
            }
            
            map.toList.filter( tmp => tmp._1.toString.toList.head != '_' && tmp._1.toString != "GLOBALS").sort{(x,y) => x._1.uniqueID < x._1.uniqueID}.map(x => x._1+" => "+typeToString(x._2)).mkString("[ ", "; ", " ]");
        }
    }

    case class TypeTransferFunction(silent: Boolean, collectAnnotations: Boolean) extends TransferFunction[TypeEnvironment, CFGStatement] {
        //def notice(msg: String, pos: Positional) = if (!silent) { new Exception(msg).printStackTrace(); Reporter.notice(msg, pos) }
        //def error(msg: String, pos: Positional) = if (!silent) { new Exception(msg).printStackTrace(); Reporter.error(msg, pos) }
        def notice(msg: String, pos: Positional) = if (!silent) Reporter.notice(msg, pos)
        def error(msg: String, pos: Positional) = if (!silent) Reporter.error(msg, pos)

        def containsUninit(t: Type) = t match {
            case TTop =>
                true
            case TUninitialized =>
                true
            case tu: TUnion =>
                tu.types contains TUninitialized
            case _ =>
                false;
        }

        def removeUninit(removeInArrays: Boolean)(t: Type): Type = t match {
            case TTop =>
                TAny
            case TUninitialized =>
                TBottom
            case tu: TUnion =>
                tu.types.map { removeUninit(removeInArrays) } reduceLeft (_ union _)
            case ta: TArray =>
                if (removeInArrays) {
                    new TArray(Map[String, Type]() ++ ta.entries.map{ e => (e._1, removeUninit(removeInArrays)(e._2)) }, removeUninit(removeInArrays)(ta.globalType))
                } else {
                    t
                }
            case _ =>
                t
        }

        def uninitToNull(t: Type): Type = t match {
            case TTop =>
                TAny
            case TUninitialized =>
                TNull
            case tu: TUnion =>
                tu.types.map { uninitToNull } reduceLeft (_ union _)
            case ta: TArray =>
                new TArray(Map[String, Type]() ++ ta.entries.map{ e => (e._1, uninitToNull(e._2)) }, uninitToNull(ta.globalType))
            case _ =>
                t
        }

        def apply(node : CFGStatement, envInit : TypeEnvironment) : TypeEnvironment = {
            var env = envInit

            def typeFromSV(sv: CFGSimpleValue): Type = sv match {
                case CFGLong(value)         => TInt
                case CFGFloat(value)        => TFloat
                case CFGString(value)       => TString
                case CFGTrue()              => TTrue
                case CFGFalse()             => TFalse
                case CFGAny()               => TAny
                case CFGNone()              => TBottom
                case CFGNull()              => TNull
                case CFGThis()              => getObject(node, env.scope)
                case CFGEmptyArray()        => new TArray()
                case CFGInstanceof(lhs, cl) => TBoolean
                case CFGArrayNext(ar)       => typeFromSV(ar)
                case CFGArrayCurElement(id: CFGSimpleVariable) =>
                    env.lookup(id) match {
                        case Some(TAnyArray) =>
                            TAny
                        case Some(t: TArray) =>
                            val et = if (t.entries.size > 0) {
                                t.entries.values.reduceLeft(_ join _)
                            } else {
                                TBottom
                            }

                            et union removeUninit(false)(t.globalType)
                        case _ =>
                            TAny
                    }
                case CFGArrayCurElement(ar) => TAny
                case CFGArrayCurKey(ar)     => TString union TInt
                case CFGArrayCurIsValid(ar) =>
                    TBoolean
                case CFGNew(cr, params) => cr match {
                    case parser.Trees.StaticClassRef(_, _, id) =>
                        GlobalSymbols.lookupClass(id.value) match {
                            case a @ Some(cs) =>
                                getObject(node, a)
                            case _ =>
                                error("Undefined class '"+id.value+"'", id)
                                getObject(node, None)
                        }
                    case _ =>
                        getObject(node, None)
                }
                case cl @ CFGClone(obj) =>
                    typeFromSV(obj) match {
                        case ref: TObjectRef =>
                            val ro = env.store.lookup(ref)
                            env = env.setStore(env.store.set(ObjectId(cl.uniqueID, 0), ro))
                            new TObjectRef(ObjectId(cl.uniqueID, 0), env.store)
                        case _ =>
                            TAnyObject
                    }
                case fcall @ CFGFunctionCall(id, args) =>
                    GlobalSymbols.lookupFunction(id.value) match {
                        case Some(fs) =>
                                if (collectAnnotations) {
                                    val ft = new TFunction(args.map(a => (typeFromSV(a), false)), TBottom)
                                    AnnotationsStore.collectFunction(fs, ft);
                                }
                                checkFCalls(fcall.params, List(functionSymbolToFunctionType(fs)), fcall)
                        case None =>
                            // handle special functions
                            id.value.toLowerCase match {
                                case "isset" | "empty" =>
                                    TBoolean // no need to check the args, this is a no-error function
                                case _ =>
                                    notice("Function "+id.value+" appears to be undefined!", id)
                                    TBottom
                            }
                    }
                case mcall @ CFGMethodCall(r, mid, args) =>
                    typeFromSV(r) match {
                        case or: TObjectRef =>
                            val ro = env.store.lookup(or);
                            ro.lookupMethod(mid.value, env.scope) match {
                                case Some(mt) =>
                                    if (collectAnnotations) {
                                        // Create a FunctionType and add it to the list of potential prototypes
                                    }
                                    checkFCalls(args, List(mt), mcall)
                                case None =>
                                    // Check for magic __call ?
                                    val cms = ro.lookupMethod("__call", env.scope)
                                    if (cms == None) {
                                        notice("Undefined method '" + mid.value + "' in object "+ro, mid)
                                        TBottom
                                    } else {
                                        cms.get.ret
                                    }
                            }
                        case _ =>
                            TBottom
                    }

                case const @ CFGConstant(id) =>
                    GlobalSymbols.lookupConstant(id.value) match {
                        case Some(cs) =>
                            cs.typ
                        case None =>
                            if (Main.verbosity > 0) {
                                notice("Undefined constant '" + id.value + "'", const)
                            }
                            TString
                    }

                case const @ CFGClassConstant(cl, id) =>
                    TAny // TODO

                case const @ CFGClassProperty(cl, index) =>
                    TAny // TODO

                case mcall @ CFGStaticMethodCall(cl, id, args) =>
                    TAny // TODO

                case tern @ CFGTernary(iff, then, elze) =>
                    typeFromSV(then) union typeFromSV(elze)

                case CFGCast(typ, v) =>
                    import parser.Trees._
                    typ match {
                        case CastUnset => TNull
                        case CastInt => TInt
                        case CastString => TString
                        case CastDouble => TFloat
                        case CastArray => TAnyArray
                        case CastBool => TBoolean
                        case CastObject => TAnyObject
                    }

                case id: CFGSimpleVariable =>
                  env.lookup(id).getOrElse(TTop)

                case CFGArrayEntry(ar, ind) =>
                    typeFromSV(ar) match {
                        case t: TArray =>
                            t.lookup(ind)
                        case _ =>
                            TBottom
                    }

                case op @ CFGObjectProperty(obj, p) =>
                    typeFromSV(obj) match {
                        case TAnyObject =>
                            TTop
                        case or: TObjectRef =>
                            env.store.lookup(or).lookupField(p)
                        case u: TUnion =>
                            u.types.map { _ match {
                                case TAnyObject =>
                                    TTop
                                case or: TObjectRef =>
                                    env.store.lookup(or).lookupField(p)
                                case _ =>
                                    TBottom
                            }}.reduceLeft(_ union _)
                        case _ =>
                            TBottom
                    }
                case CFGNextArrayEntry(arr) =>
                    typeFromSV(arr) match {
                        case t: TArray =>
                            t.globalType
                        case _ =>
                            TBottom
                   }

                case vv @ CFGVariableVar(v) =>
                    notice("Dynamic variable ignored", vv)
                    TTop

                case u =>
                  println("Unknown simple value: "+u)
                  TTop
            }

            def getObject(node: CFGStatement, ocs: Option[ClassSymbol]): ObjectType = {
                val id = ObjectId(node.uniqueID, 0);
                env = env.setStore(env.store.initIfNotExist(id, ocs))
                new TObjectRef(id, env.store)
            }

            def expOrRef(v1: CFGSimpleValue, typs: Type*): Type = {
                val vtyp = typeFromSV(v1)
                var vtypCheck = vtyp
                val etyp = typs reduceLeft (_ join _)

                // if verbosity is == 0, we remove Uninit from the type used in the check
                if (Main.verbosity == 0) {
                    vtypCheck = removeUninit(true)(vtypCheck)
                }

                if (vtypCheck leq etyp) {
                    uninitToNull(vtyp)
                } else {
                    def errorKind(kind: String) = {
                        if (!silent) {
                            if (containsUninit(vtypCheck)) {
                                notice("Potentially undefined "+kind+": "+stringRepr(v1), v1)
                            } else {
                                if (vtypCheck != TAny || Main.verbosity > 0) {
                                    notice("Potential type mismatch: expected: "+typs.toList.map(_ toText).mkString(" or ")+", found: "+vtypCheck.toText, v1)
                                }
                            }
                        }
                    }

                    v1 match {
                        case sv: CFGSimpleVariable =>
                            errorKind("variable")
                            val (tenv, t) = TypeLattice.meet(env, etyp, vtyp)
                            env = tenv.inject(sv, t)
                            t

                        case v: CFGArrayEntry =>
                            errorKind("array entry")
                            val (sv, evtyp) = getCheckType(v, etyp)
                            val svtyp = typeFromSV(sv)

                            val (tenv, t) = TypeLattice.meet(env, etyp, vtyp)
                            env = tenv.inject(sv, t)
                            t

                        case v: CFGObjectProperty =>
                            errorKind("object property")
                            val (sv, evtyp) = getCheckType(v, etyp)
                            val svtyp = typeFromSV(sv)

                            val (tenv, t) = TypeLattice.meet(env, etyp, vtyp)
                            env = tenv.inject(sv, t)
                            t

                        case _ =>
                            if (!silent && (vtypCheck != TAny || Main.verbosity > 0)) {
                                notice("Potential type mismatch: expected: "+typs.toList.map(_ toText).mkString(" or ")+", found: "+vtypCheck.toText, v1)
                            }
                           etyp

                    }
                }
            }

            def typeFromUnOP(op: CFGUnaryOperator, v1: CFGSimpleValue): Type = op match {
                case BOOLEANNOT =>
                    expOrRef(v1, TAny)
                case BITSIWENOT =>
                    expOrRef(v1, TInt)
                case PREINC =>
                    expOrRef(v1, TInt)
                case POSTINC =>
                    expOrRef(v1, TInt)
                case PREDEC =>
                    expOrRef(v1, TInt)
                case POSTDEC =>
                    expOrRef(v1, TInt)
                case SILENCE =>
                    expOrRef(v1, TAny)
            }

            def typeFromBinOP(v1: CFGSimpleValue, op: CFGBinaryOperator, v2: CFGSimpleValue): Type = op match {
                case PLUS | MINUS | MULT | DIV  =>
                    expOrRef(v1, TInt, TFloat) match {
                        case TInt =>
                            expOrRef(v2, TInt, TFloat)
                        case TFloat =>
                            expOrRef(v2, TInt, TFloat)
                            TFloat
                        case _ =>
                            expOrRef(v2, TInt, TFloat)
                    }
                case MOD =>
                    expOrRef(v1, TInt)
                    expOrRef(v2, TInt)
                    TInt
                case CONCAT =>
                    expOrRef(v1, TAny)
                    expOrRef(v2, TAny)
                    TString
                case INSTANCEOF =>
                    expOrRef(v1, TAnyObject)
                    expOrRef(v2, TString)
                    TBoolean
                case BITWISEAND | BITWISEOR | BITWISEXOR =>
                    expOrRef(v1, TInt)
                    expOrRef(v2, TInt)
                case SHIFTLEFT | SHIFTRIGHT =>
                    expOrRef(v1, TInt)
                    expOrRef(v2, TInt)
                case BOOLEANAND | BOOLEANOR | BOOLEANXOR | LT | LEQ | GEQ | GT |
                     EQUALS | IDENTICAL | NOTEQUALS | NOTIDENTICAL=>
                    expOrRef(v1, TAny)
                    expOrRef(v2, TAny)
                    TBoolean
            }

            def getCheckType(sv: CFGSimpleValue, ct: Type): (CFGSimpleVariable, Type) = sv match {
                case CFGVariableVar(v) =>
                    getCheckType(v, TString)
                case CFGArrayEntry(arr, index) =>
                    typeFromSV(arr) match {
                        case TString =>
                            // If arr is known to be a string, index must be Int
                            expOrRef(index, TInt)
                            getCheckType(arr, TString)
                        case to: ObjectType =>
                            Predef.error("TODO: object[index] not yet implemented")
                        case _ =>
                            expOrRef(index, TString, TInt)
                            val newct = if (ct == TTop) {
                                TAnyArray
                            } else {
                                new TArray().setAny(TTop).inject(index, ct)
                            }
                            getCheckType(arr, newct)
                    }
                case CFGNextArrayEntry(arr) =>
                    getCheckType(arr, TAnyArray)
                case CFGObjectProperty(obj, prop) =>
                    // IGNORE for now, focus on arrays
                    getCheckType(obj, TAnyObject)
                case svar: CFGSimpleVariable =>
                    (svar, ct)
                case _ =>
                    Predef.error("Woops, unexpected CFGVariable inside checktype of!")

            }

            def assign(v: CFGVariable, ext: Type): Type = {
                val (svar, ct) = getCheckType(v, TTop)

                //println("Assigning "+v+" to "+ext)
                //println("Checking "+svar+"("+typeFromSV(svar)+") against "+ct)
                val reft = expOrRef(svar, ct)
                //println("After refinement: "+reft)

                // Now, we need to get down in that variable and affect the type as the assign require
                def backPatchType(sv: CFGSimpleValue, typ: Type): Type = sv match {
                    case CFGVariableVar(v) =>
                        backPatchType(v, TString)
                    case CFGArrayEntry(arr, index) =>
                        val t = typeFromSV(arr) match {
                            case ta: TArray =>
                                ta.inject(index, typ)
                            case _ =>
                                new TArray().inject(index, typ)
                        }
                        backPatchType(arr, t)
                    case CFGNextArrayEntry(arr) =>
                        val t = typeFromSV(arr) match {
                            case ta: TArray =>
                                ta.setAny(typ union ta.globalType)
                            case _ =>
                                new TArray().setAny(typ union TUninitialized)
                        }
                        backPatchType(arr, t)
                    case CFGObjectProperty(obj, prop) =>
                        def updateObject(obj: TObjectRef) {
                            val ro =
                                if (obj.id.pos < 0) {
                                    // $this is always using strong updates
                                    obj.realObject.injectField(prop, typ)
                                } else {
                                    obj.realObject.injectField(prop, typ union obj.realObject.lookupField(prop))
                                }
                            env = env.setObject(obj.id, ro)
                        }
                        val t = typeFromSV(obj) match {
                            case to: TObjectRef =>
                                updateObject(to)
                                to
                            case tu: TUnion =>
                                for (f <- tu.types) f match {
                                    case to: TObjectRef =>
                                        updateObject(to)
                                    case _ =>
                                }

                                tu
                            case _ =>
                                TAnyObject
                        }
                        backPatchType(obj, t)
                    case svar: CFGSimpleVariable =>
                        typ
                    case _ =>
                        Predef.error("Woops, unexpected CFGVariable inside checktype of!")
                }

                env = env.inject(svar, reft)
                //println("Refined type: "+reft)
                val rest = backPatchType(v, ext)
                //println("Backpatched type: "+rest)
                env = env.inject(svar, rest)
                rest
            }

            def functionSymbolToFunctionType(fs: FunctionSymbol): FunctionType = {
                new TFunction(fs.argList.map { a => (a._2.typ, a._2.optional) }, fs.typ)
            }

            def checkFCalls(fcall_params: List[CFGSimpleValue], syms: List[FunctionType], pos: Positional) : Type =  {
                def protoFilter(sym: FunctionType): Boolean = {
                    sym match {
                        case tf: TFunction =>
                            var ret = true;
                            for (i <- fcall_params.indices) {
                                if (i >= tf.args.length) {
                                    ret = false
                                } else {
                                    if (!(typeFromSV(fcall_params(i)) leq tf.args(i)._1)) {
                                        //notice("Prototype mismatch because "+fcall.params(i)+"("+typeFromSV(fcall.params(i))+") </: "+args(i)._1) 

                                        ret = false;
                                    }
                                }
                            }
                            ret
                        case TFunctionAny =>
                            true
                    }
                }

                syms filter protoFilter match {
                    case Nil =>
                        if (syms.size > 1) {
                            error("Unmatched function prototype '("+fcall_params.map(x => typeFromSV(x)).mkString(", ")+")', candidates are:\n    "+syms.mkString(",\n    "), pos)
                            TBottom
                        } else {
                            syms.first match {
                                case tf: TFunction =>
                                    for (i <- fcall_params.indices) {
                                        if (i >= tf.args.length) {
                                            error("Prototype error!", pos)
                                        } else {
                                            expOrRef(fcall_params(i), tf.args(i)._1)
                                        }

                                    }
                                    tf.ret
                                case s =>
                                    s.ret
                            }
                        }

                    case f :: xs =>
                        f.ret
                }
            }

            node match {
                case CFGAssign(vr: CFGVariable, v1) =>
                    val t = expOrRef(v1, TAny)
                    assign(vr, t)

                case CFGAssignUnary(vr: CFGVariable, op, v1) =>
                    // We want to typecheck v1 according to OP
                    val t = typeFromUnOP(op, v1);
                    assign(vr, t)

                case CFGAssignBinary(vr: CFGVariable, v1, op, v2) =>
                    // We want to typecheck v1/v2 according to OP
                    val t = typeFromBinOP(v1, op, v2)
                    assign(vr, t)

                case CFGAssume(v1, op, v2) => op match {
                    case LT | LEQ | GEQ | GT =>
                        expOrRef(v1, TInt, TFloat)
                        expOrRef(v2, TInt, TFloat)
                    case EQUALS | IDENTICAL | NOTEQUALS | NOTIDENTICAL =>
                        /**
                         * Type filtering:
                         * if v1 is a variable that is compared against True/False
                         * we can filter incompatible values out
                         */
                        def filter(v: CFGSimpleVariable, value: Boolean) = {
                            typeFromSV(v) match {
                                case u: TUnion =>
                                    val typ = if (value) {
                                        u.types.filter(t => t != TFalse && t != TNull).map(t => if(t == TBoolean) TTrue else t).reduceLeft(_ union _)
                                    } else {
                                        u.types.filter(t => t != TTrue  && t != TResource).map(t => if(t == TBoolean) TFalse else t).reduceLeft(_ union _)
                                    }

                                    env = env.inject(v, typ)
                                case t =>
                                    if (value) {
                                        if (t != TFalse && t != TNull) {
                                            env = env.inject(v, t)
                                        } else {
                                            // we had a single incompatible type
                                            // The branch will never be taken!
                                            env = BaseTypeEnvironment
                                        }
                                    } else {
                                        if (t != TTrue && t != TResource) {
                                            env = env.inject(v, t)
                                        } else {
                                            // we had a single incompatible type
                                            // The branch will never be taken!
                                            env = BaseTypeEnvironment
                                        }
                                    }
                            }
                        }
                        var t1 = v1;
                        var t2 = v2;
                        (v1, typeFromSV(v1), v2, typeFromSV(v2)) match {
                            case (v: CFGSimpleVariable, TTrue , w: CFGSimpleVariable, _) =>
                                // we benefit from a switch:
                                t1 = v2;
                                t2 = v1;
                            case (v: CFGSimpleVariable, TFalse , w: CFGSimpleVariable, _) =>
                                // we benefit from a switch:
                                t1 = v2;
                                t2 = v1;
                            case (v: CFGSimpleVariable, _, _, _) =>
                                // no change
                            case (_, _, v: CFGSimpleVariable, _) =>
                                t1 = v2;
                                t2 = v1;
                            case _ =>
                                // no change, will be ignored anyway
                        }
                        (t1, op, typeFromSV(t2)) match  {
                            case (v: CFGSimpleVariable, EQUALS, TFalse)  =>
                                filter(v, false)
                            case (v: CFGSimpleVariable, NOTEQUALS, TFalse)  =>
                                filter(v, true)
                            case (v: CFGSimpleVariable, EQUALS, TTrue)  =>
                                filter(v, true)
                            case (v: CFGSimpleVariable, NOTEQUALS, TTrue)  =>
                                filter(v, false)
                            case _ =>
                                expOrRef(v1, TAny)
                                expOrRef(v2, TAny)
                        }

                  }

                case CFGPrint(v) =>
                    expOrRef(v, TInt, TString, TAnyObject, TBoolean)

                case CFGUnset(id) =>
                    id match {
                        case v: CFGSimpleVariable =>
                            env = env.inject(v, TUninitialized)
                        case _ =>
                            // TODO

                    }

                case ex: CFGSimpleValue =>
                    expOrRef(ex, TAny)

                case _ => notice(node+" not yet handled", node); env
            }

            env
        }
    }

    case class Analyzer(cfg: CFG, scope: Scope) {

        def setupEnvironment: TypeEnvironment = {
            var baseEnv   = new TypeEnvironment;

            // We now inject predefined variables
            def injectPredef(name: String, typ: Type): Unit = {
                scope.lookupVariable(name) match {
                    case Some(vs) =>
                        baseEnv = baseEnv.inject(CFGIdentifier(vs), typ)
                    case None =>
                        // ignore this var
                        println("Woops, no such symbol found: "+name)
                }
            }

            //scope.registerPredefVariables
            injectPredef("_GET",     new TArray(TBottom))
            injectPredef("_POST",    new TArray(TBottom))
            injectPredef("GLOBALS",  new TArray(TBottom))
            injectPredef("_REQUEST", new TArray(TBottom))
            injectPredef("_COOKIE",  new TArray(TBottom))
            injectPredef("_SERVER",  new TArray(TBottom))
            injectPredef("_ENV",     new TArray(TBottom))
            injectPredef("_SESSION", new TArray(TBottom))

            // for methods, we inject $this as its always defined
            scope match {
                case ms: MethodSymbol =>
                    baseEnv = baseEnv.setStore(baseEnv.store.initIfNotExist(ObjectId(-1, 0), Some(ms.cs)))
                    injectPredef("this", new TObjectRef(ObjectId(-1, 0), baseEnv.store))
                case _ =>
            }

            // in case we have a function or method symbol, we also inject arguments
            scope match {
                case fs: FunctionSymbol =>
                    for ((name, sym) <- fs.argList) {
                        baseEnv = baseEnv.inject(CFGIdentifier(sym), sym.typ)
                    }
                case _ =>

            }

            baseEnv
        }

        def analyze: Unit = {
            val bottomEnv = BaseTypeEnvironment;
            val baseEnv   = setupEnvironment;

            val aa = new AnalysisAlgorithm[TypeEnvironment, CFGStatement](TypeTransferFunction(true, false), bottomEnv, baseEnv, cfg)

            aa.init
            aa.computeFixpoint

            if (Main.displayFixPoint) {
                println("     - Fixpoint:");
                for ((v,e) <- aa.getResult.toList.sort{(x,y) => x._1.name < y._1.name}) {
                    println("      * ["+v+"] => "+e);
                }
            }

            // Detect unreachables:
            for (l <- aa.detectUnreachable(TypeTransferFunction(true, false))) {
                Reporter.notice("Unreachable code", l)
            }

            // Collect errors and annotations
            aa.pass(TypeTransferFunction(false, !Main.exportAPIPath.isEmpty))

            // Collect retvals
            scope match {
                case fs: FunctionSymbol =>
                    // collect return value
                    val facts = aa.getResult;
                    val retType = facts(cfg.exit).map.getOrElse(CFGTempID("retval"), TBottom);

                    AnnotationsStore.collectFunctionRet(fs, retType)
                case _ =>
            }
        }
    }
}
