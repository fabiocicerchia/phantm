package phantm.annotations

sealed trait Annotation

trait TypeAnnotation extends Annotation {
    import phantm.types.{Type, TAny}

    var typ: Type = TAny

    def annotateFromT(from: TypeAnnotation): this.type = { typ = from.typ; this }
}

trait FunctionTypeAnnotation extends Annotation {
    import phantm.types.{FunctionType, TFunctionAny}

    var ftyps = Set[FunctionType]()

    def registerFType(ftyp: FunctionType): this.type = { ftyps += ftyp; this }

    def annotateFromFT(from: FunctionTypeAnnotation): this.type = { ftyps = from.ftyps; this }
}

trait FunctionAnnotation extends Annotation {
    var shouldInline = false
    var isPure = false
}

trait CommentAnnotation  extends Annotation {
    var comment: Option[String] = None

    def attachComment(com: Option[String]): this.type = {
        comment = com
        this
    }

    def annotateFromC(from: CommentAnnotation): this.type = { comment = from.comment; this }
}

