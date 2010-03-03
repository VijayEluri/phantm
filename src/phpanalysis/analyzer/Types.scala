package phpanalysis.analyzer;
import Symbols._
import scala.collection.immutable.{Map, Set}

import controlflow.TypeFlow._
import controlflow.CFGTrees._
import parser.Trees._

object Types {
    object RecProtection {
        var objectToStringDeep = 0;
    }

    sealed abstract class Type {
        self=>

        def union(t: Type) = TypeLattice.join(this, t)
        def join(t: Type) = union(t)

        def equals(t: Type) = t == self;

        def deepNess(st: ObjectStore): Int = 1;

        def toText(te: TypeEnvironment) = toString
    }

    sealed abstract class ConcreteType extends Type;

    // Classes types
    sealed abstract class ClassType {
        def isSubtypeOf(cl2: ClassType): Boolean;
    }
    case object TClassAny extends ClassType {
        def isSubtypeOf(cl2: ClassType) = true;
    }

    class TClass(val cs: ClassSymbol) extends ClassType {
        override def toString = cs.name
        def isSubtypeOf(cl2: ClassType) = cl2 match {
            case TClassAny => false
            case tc: TClass =>
                cs.subclassOf(tc.cs)
        }
    }

    // Functions types
    sealed abstract class FunctionType {
        val ret: Type;
    }
    object TFunctionAny extends FunctionType {
        val ret = TAny
    }
    class TFunction(val args: List[(Type, Boolean)], val ret: Type) extends FunctionType {

        override def toString = args.map{a => a match {
                case (t, false) => t
                case (t, true) => "["+t+"]"
            }}.mkString("(", ", ", ")")+" => "+ret
    }

    abstract class RealObjectType {
        self =>

        import controlflow.CFGTrees._

        var fields: Map[String, Type]
        var pollutedType: Option[Type]

        def lookupField(index: String): Option[Type];

        def deepNess(st: ObjectStore): Int;

        def lookupField(index: CFGSimpleValue): Option[Type] = index match {
          case CFGLong(i)        => lookupField(i+"")
          case CFGString(index) => lookupField(index)
          case _ => pollutedType
        }

        def lookupMethod(index: String, from: Option[ClassSymbol]): Option[FunctionType];

        def lookupMethod(index: CFGSimpleValue, from: Option[ClassSymbol]): Option[FunctionType] = index match {
            case CFGLong(i)        => lookupMethod(i+"", from)
            case CFGString(index) => lookupMethod(index, from)
            case _ => None
        }

        def injectField(index: String, typ: Type, weak: Boolean) : self.type;

        def injectField(index: String, typ: Type) : self.type =
            injectField(index, typ, true)

        def injectField(index: CFGSimpleValue, typ: Type): this.type =
            injectField(index, typ, true)

        def injectField(index: CFGSimpleValue, typ: Type, weak: Boolean): this.type = index match {
          case CFGLong(i)        => injectField(i+"", typ, weak)
          case CFGString(index) => injectField(index, typ, weak)
          case _ => polluteFields(typ)
        }

        def polluteFields(typ: Type): self.type;
        def merge(t2: RealObjectType): RealObjectType;
        def duplicate: RealObjectType;

        def toText(te: TypeEnvironment) = toString
    }

    // Objects related types
    case class ObjectId(val pos: Int, val offset: Int)

    // Stores the ref => Real Objects relashionship
    case class ObjectStore(val store: Map[ObjectId, RealObjectType]) {

        def this() = this(Map[ObjectId, RealObjectType]())

        def union(os: ObjectStore) : ObjectStore = {
            var res = new ObjectStore()

            for (id <- this.store.keySet ++ os.store.keySet) {
                val c1 = this.store.contains(id);
                val c2 =   os.store.contains(id);

                if (c1 && c2) {
                    res = res.set(id, this.store(id) merge os.store(id))
                } else if (c1) {
                    res = res.set(id, this.store(id).duplicate)
                } else {
                    res = res.set(id, os.store(id).duplicate)
                }
            }

            res

        }

        def lookup(id: TObjectRef): RealObjectType = lookup(id.id);

        def lookup(id: ObjectId): RealObjectType = store.get(id) match {
            case Some(o) => o
            case None => error("Woops incoherent store")
        }

        def set(id: ObjectId, robj: RealObjectType): ObjectStore = new ObjectStore(store.update(id, robj));

        def initIfNotExist(id: ObjectId, ocs: Option[ClassSymbol]) : ObjectStore = store.get(id) match {
            case Some(_) =>
                this
            case None =>
                // We create a new object and place it in the store
                val rot = ocs match {
                    case Some(cs) =>
                        // construct a default object for this class
                        new TRealClassObject(new TClass(cs), Map[String,Type]() ++ cs.properties.mapElements[Type] { x => x.typ }, None)
                    case None =>
                        // No class => any object
                        new TRealObject(Map[String,Type](), None)
                }

                set(id, rot);
        }

        override def toString = {
            store.toList.sort{(x,y) => x._1.pos < x._1.pos}.map(x => "("+x._1.pos+","+x._1.offset+") => "+x._2).mkString("{ ", "; ", " }");
        }
    }

    // Object types exposed to symbols
    abstract class ObjectType extends ConcreteType

    // Any object, should be only used to typecheck, no symbol should be infered to this type
    object TAnyObject extends ObjectType {
        override def toString = "TAnyObject"
        override def toText(te: TypeEnvironment)   = "any object"
    }
    // Reference to an object in the store
    class TObjectRef(val id: ObjectId) extends ObjectType {
        override def toString = {
            "TObjectRef#"+id+""
        }

        override def toText(te: TypeEnvironment) = {
            te.store.lookup(id).toText(te)
        }

        override def equals(v: Any) = v match {
            case ref: TObjectRef =>
                ref.id == id
            case _ => false
        }

        override def deepNess(st: ObjectStore) = {
            st.lookup(this).deepNess(st)
        }
    }

    // Real object type (in the store) representing a specific object of any class
    class TRealObject(var fields: Map[String, Type],
                      var pollutedType: Option[Type]) extends RealObjectType {

        override def equals(o: Any): Boolean = o match {
            case ro: TRealObject =>
                fields == ro.fields && pollutedType == ro.pollutedType
            case _ =>
                false
        }

        def deepNess(st: ObjectStore): Int = {
            var max = 0;
            for (v <- fields.values) {
                if (max < v.deepNess(st)) max = v.deepNess(st);
            }

            pollutedType match {
                case Some(t) =>
                    if (t.deepNess(st) > max)
                        max = t.deepNess(st)
                case None =>
            }

            max+1;
        }


        def lookupField(index: String) =
            fields.get(index) match {
                case Some(t) => Some(t)
                case None => pollutedType
            }

        def lookupMethod(index: String, from: Option[ClassSymbol]): Option[FunctionType] =
            None

        def injectField(index: String, typ: Type, weak: Boolean): this.type = {
            /*
            println("Injecting field "+index+" -> "+typ)
            println("ON: "+fields)
            */
            fields(index) = (if (weak) TypeLattice.join(typ, fields.getOrElse(index, TUninitialized)) else typ)
            this
        }

        override def toString = {
            RecProtection.objectToStringDeep += 1;
            var r = "Object(?)"
            if (RecProtection.objectToStringDeep < 2) {
                r = r+"["+(fields.map(x => x._1 +" => "+ x._2).mkString("; "))+(if(pollutedType != None) " ("+pollutedType.get+")" else "")+"]"
            } else {
                r = r+"[...]"
            }
            RecProtection.objectToStringDeep -= 1;
            r
        }

        def polluteFields(typ: Type) = {

            // When the index is unknown, we have to pollute every entries
            for ((i,t) <- fields) {
                fields(i) = t union typ
            }
            // we flag the array to allow lookup to return Any instead of None
            // since the key=>value relationship is not longer safe
            pollutedType = pollutedType match { 
                case Some(pt) => Some(pt union typ)
                case None => Some(typ)
            }

            this
        }

        def merge(a2: RealObjectType): RealObjectType = {
            // Pick superclass class, and subclass methods
            val newcl = (this, a2) match {
                case (o1: TRealClassObject, o2: TRealClassObject) =>
                    if (o1.cl.isSubtypeOf(o2.cl)) {
                        Some(o2.cl)
                    } else if (o2.cl.isSubtypeOf(o1.cl)) {
                        Some(o1.cl)
                    } else {
                        None
                    }
                case _ =>
                    None
            }

            val newPollutedType = (pollutedType, a2.pollutedType) match {
                case (Some(pt1), Some(pt2)) => Some(TypeLattice.join(pt1, pt2))
                case (Some(pt1), None) => Some(pt1)
                case (None, Some(pt2)) => Some(pt2)
                case (None, None) => None
            }

            val newFields = Map[String, Type]() ++ fields;

            for((index, typ)<- a2.fields) {
                newFields(index) = newFields.get(index) match {
                    case Some(t) => TypeLattice.join(t, typ)
                    case None => typ
                }
            }

            newcl match {
                case Some(cl) =>
                    new TRealClassObject(cl, newFields, newPollutedType)
                case None =>
                    new TRealObject(newFields, newPollutedType)
            }
        }

        def duplicate =
            new TRealObject(Map[String, Type]() ++ fields, pollutedType)
    }

    class TRealClassObject(val cl: TClass,
                           initFields: Map[String, Type],
                           initPollutedType: Option[Type]) extends TRealObject(initFields, initPollutedType){

        override def toString = {
            RecProtection.objectToStringDeep += 1;
            var r = "Object("+cl+")"
            if (RecProtection.objectToStringDeep < 2) {
                r = r+"["+(fields.map(x => x._1 +" => "+ x._2).mkString("; "))+(if(pollutedType != None) " ("+pollutedType.get+")" else "")+"]"
                r = r+"["+(cl.cs.methods.map(x => x._1+": "+msToTMethod(x._2)).mkString("; "))+"]"
            } else {
                r = r+"[...]"
            }
            RecProtection.objectToStringDeep -= 1;
            r
        }

        def msToTMethod(ms: MethodSymbol) = {
            new TFunction(ms.argList.map{ x => (x._2.typ, x._2.optional)}.toList, ms.typ)
        }

        override def lookupMethod(index: String, from: Option[ClassSymbol]) =
            cl.cs.lookupMethod(index, from) match {
                case LookupResult(Some(ms), _, _) =>
                    // found method, ignore visibility errors, for now
                    // Type hints
                    Some(msToTMethod(ms))

                case LookupResult(None, _, _) =>
                    None
            }

        override def duplicate =
            new TRealClassObject(cl, Map[String, Type]() ++ fields, pollutedType)
    }

    class TArray(val entries: Map[String, Type], val globalType: Type) extends ConcreteType {

        def this() =
            this(Map[String, Type](), TUninitialized)

        def this(global: Type) =
            this(Map[String, Type](), global)

        def lookup(index: String): Type =
            entries.getOrElse(index, globalType)

        def lookup(index: CFGSimpleValue): Type = index match {
          case CFGLong(i)       => lookup(i+"")
          case CFGString(index) => lookup(index)
          case _ => globalType
        }

        def inject(index: String, typ: Type): TArray =
            new TArray(entries + (index -> typ), globalType)

        def inject(index: CFGSimpleValue, typ: Type): TArray = index match {
          case CFGLong(i)       => inject(i+"", typ)
          case CFGString(index) => inject(index, typ)
          case _ => injectAny(typ)
        }

        def injectAny(typ: Type): TArray = {
            // When the index is unknown, we have to pollute every entries
            var newEntries = Map[String, Type]();
            for ((i,t) <- entries) {
                newEntries = newEntries + (i -> (t union typ))
            }

            new TArray(newEntries, globalType union typ)
        }

        def merge(a2: TArray): TArray = {
            var newEntries = Map[String, Type]() ++ entries;

            for((index, typ)<- a2.entries) {
                newEntries = newEntries.update(index, newEntries.get(index) match {
                    case Some(t) => TypeLattice.join(t, typ)
                    case None => TypeLattice.join(typ, TUninitialized)
                })
            }

            new TArray(newEntries, globalType union a2.globalType)
        }

        override def equals(t: Any): Boolean = t match {
            case ta: TArray =>
                entries == ta.entries && globalType == ta.globalType
            case _ => false
        }

        override def deepNess(st: ObjectStore) = {
            var max = 0;
            for (v <- entries.values) {
                if (max < v.deepNess(st)) max = v.deepNess(st);
            }

            if (globalType.deepNess(st) > max) {
                max = globalType.deepNess(st)
            }

            max+1;
        }

        override def toString =
            "Array["+(entries.map(x => x._1 +" => "+ x._2).toList :: "? => "+globalType :: Nil).mkString("; ")+"]"
    }

    object TAnyArray extends TArray(Map[String, Type](), TTop) {
        override def toString = "Array[?]"
        override def toText(te: TypeEnvironment) = "any array"
    }

    case object TInt extends ConcreteType {
        override def toText(te: TypeEnvironment) = "int"
    }
    case object TBoolean extends ConcreteType {
        override def toText(te: TypeEnvironment) = "boolean"
    }
    case object TTrue extends ConcreteType {
        override def toText(te: TypeEnvironment) = "true"
    }
    case object TFalse extends ConcreteType {
        override def toText(te: TypeEnvironment) = "false"
    }

    case object TFloat extends ConcreteType {
        override def toText(te: TypeEnvironment) = "float"
    }
    case object TString extends ConcreteType {
        override def toText(te: TypeEnvironment) = "string"
    }
    case object TAny extends ConcreteType {
        override def toText(te: TypeEnvironment) = "any"
    }
    case object TResource extends ConcreteType {
        override def toText(te: TypeEnvironment) = "resource"
    }
    case object TNull extends ConcreteType {
        override def toText(te: TypeEnvironment) = "null"
    }

    /* Special types */
    case object TTop extends Type {
        override def toText(te: TypeEnvironment) = "top"
    }

    case object TBottom extends Type {
        override def toText(te: TypeEnvironment) = "bottom"
    }

    case object TUninitialized extends Type {
        override def toText(te: TypeEnvironment) = "uninitialized"
    }

    class TUnion extends ConcreteType {
        var types: List[Type] = Nil

        def add(t: Type): Unit = t match {
            case t1: TUnion =>
                for (t2 <- t1.types) {
                    add(t2)
                }
            case TAnyArray =>
                // we can ignore any array type in the union
                types = t :: types.filter{! _.isInstanceOf[TArray]}.toList

            case t: TArray =>

                if (types contains TAnyArray) {
                    // we ignore
                } else {
                    var nt: List[Type] = Nil
                    var toAdd = t

                    for (tc <- types) tc match {
                        case t: TArray =>
                            toAdd = toAdd merge t
                        case _ =>
                            nt = tc :: nt
                    }

                    types = toAdd :: nt;

                }
            case t =>
                if (!(types contains t)) {
                    types = t :: types
                }
        }

        override def equals(t: Any): Boolean = t match {
            case tu: TUnion =>
                if (tu.types.size == types.size) {
                    for (v <- types) {
                        if (!(tu.types contains v)) {
                            return false;
                        }
                    }
                    true
                } else {
                    false
                }
            case _ => false
        }

        override def toString = types.mkString("{", ",", "}")
        override def toText(te: TypeEnvironment)   = types.map { x => x.toText(te) }.mkString(" or ")
    }

    object TUnion extends ConcreteType {
        def apply(t1: TUnion, t2: Type): Type = {
            t1 add t2
            t1
        }
        def apply(t1: Type, t2: TUnion): Type = {
            t2 add t1
            t2
        }
        def apply(ts: Iterable[Type]): Type = {
            val t = new TUnion;

            for (ta <- ts) t add ta

            if (t.types.size == 1) {
                t.types.toList.head
            } else if(t.types.size == 0) {
                TBottom
            } else {
                t
            }
        }
        def apply(t1: Type, t2: Type): Type = {
            if (t1 == t2) {
                t1
            } else {
                val t = new TUnion;
                t add t1
                t add t2
                t
            }
        }

    }


    trait Typed {
        self =>

        private var _tpe: Type = TAny

        def setType(tpe: Type): self.type = { _tpe = tpe; this }
        def getType: Type = _tpe
    }


    def typeHintToType(oth: Option[TypeHint]): Type = oth match {
        case Some(a) => typeHintToType(a)
        case None => TAny;
    }

    def typeHintToType(th: TypeHint): Type = th match {
        case THString => TString
        case THAny => TAny
        case THFalse => TFalse
        case THTrue => TTrue
        case THResource => TResource
        case THInt => TInt
        case THBoolean => TBoolean
        case THFloat => TFloat
        case THNull => TNull
        case THArray => TAnyArray
        case THAnyObject => TAnyObject
        case THObject(StaticClassRef(_, _, id)) =>
            GlobalSymbols.lookupClass(id.value) match {
                case Some(cs) =>
    //                ObjectStore.getOrCreateTMP(Some(cs))
                    TAnyObject
                case None =>
                    println("Woops, undefined class "+id.value)
                    TAnyObject
            }
        case u: THUnion =>
            TUnion(typeHintToType(u.a), typeHintToType(u.b))
    }
}
