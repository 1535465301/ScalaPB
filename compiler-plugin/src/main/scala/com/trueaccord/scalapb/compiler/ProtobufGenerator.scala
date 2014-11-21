package com.trueaccord.scalapb.compiler

import com.google.protobuf.Descriptors.FieldDescriptor.{Type, JavaType}
import com.google.protobuf.Descriptors._
import com.google.protobuf.{CodedOutputStream, ByteString}
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}
import scala.collection.JavaConversions._
import scala.collection.immutable.IndexedSeq

object ProtobufGenerator {
  sealed trait NamedField extends Any {
    def scalaName: String
    def upperScalaName: String
  }

  implicit class FieldDescriptorPimp(val fd: FieldDescriptor) extends AnyVal with NamedField {
    def containingOneOf: Option[OneofDescriptor] = Option(fd.getContainingOneof)
    def isInOneof: Boolean = containingOneOf.isDefined
    def scalaName: String = snakeCaseToCamelCase(fd.getName)
    def upperScalaName: String = snakeCaseToCamelCase(fd.getName, true)
  }

  implicit class OneofDescriptorPimp(val oneof: OneofDescriptor) extends AnyVal with NamedField {
    def scalaName = snakeCaseToCamelCase(oneof.getName)
    def upperScalaName = snakeCaseToCamelCase(oneof.getName, true)
    def fields: IndexedSeq[FieldDescriptor] = (0 until oneof.getFieldCount).map(oneof.getField)
  }

  implicit class MessageDescriptorPimp(val message: Descriptor) extends AnyVal {
    def fields = message.getFields
    def fieldsWithoutOneofs = fields.filterNot(_.isInOneof)
  }

  val SCALA_RESERVED_WORDS = Set(
    "abstract", "case", "catch", "class", "def",
    "do", "else", "extends", "false", "final",
    "finally", "for", "forSome", "if", "implicit",
    "import", "lazy", "match", "new", "null",
    "object", "override", "package", "private", "protected",
    "return", "sealed", "super", "this", "throw",
    "trait", "try", "true", "type", "val",
    "var", "while", "with", "yield",
    "val", "var", "def", "if", "ne", "case")

  implicit class AsSymbolPimp(val s: String) extends AnyVal {
    def asSymbol: String = if (SCALA_RESERVED_WORDS.contains(s)) s"`$s`" else s
  }

  def handleCodeGeneratorRequest(request: CodeGeneratorRequest): CodeGeneratorResponse = {
    val fileProtosByName = request.getProtoFileList.map(n => n.getName -> n).toMap
    val b = CodeGeneratorResponse.newBuilder
    val filesByName: Map[String, FileDescriptor] =
      request.getProtoFileList.foldLeft[Map[String, FileDescriptor]](Map.empty) {
        case (acc, fp) =>
          val deps = fp.getDependencyList.map(acc)
          acc + (fp.getName -> FileDescriptor.buildFrom(fp, deps.toArray))
      }
    request.getFileToGenerateList.foreach {
      name =>
        val file = filesByName(name)
        val responseFile = generateFile(file)
        b.addFile(responseFile)
    }
    b.build
  }

  def javaPackage(file: FileDescriptor): String = {
    if (file.getOptions.hasJavaPackage)
      file.getOptions.getJavaPackage
    else file.getPackage
  }

  def javaPackageAsSymbol(file: FileDescriptor): String =
    javaPackage(file).split('.').map(_.asSymbol).mkString(".")

  def scalaFullOuterObjectName(file: FileDescriptor) = {
    val pkg = javaPackageAsSymbol(file)
    if (pkg.isEmpty) scalaOuterObjectName(file)
    else pkg + "." + scalaOuterObjectName(file)
  }

  def javaFullOuterClassName(file: FileDescriptor) = {
    val pkg = javaPackageAsSymbol(file)
    if (pkg.isEmpty) javaOuterClassName(file)
    else pkg + "." + javaOuterClassName(file)
  }

  def fullNameFromBase(base: String, fullName: String, file: FileDescriptor) = {
    val nameWithoutPackage = if (file.getPackage.isEmpty) fullName
    else fullName.substring(file.getPackage.size + 1)
    if (base.isEmpty) nameWithoutPackage else (base + "." + nameWithoutPackage)
  }

  def fullJavaName(fullName: String, file: FileDescriptor) =
    fullNameFromBase(javaFullOuterClassName(file), fullName, file)

  def fullScalaName(fullName: String, file: FileDescriptor) = {
    val s = fullNameFromBase(scalaFullOuterObjectName(file), fullName, file)
    val (prefix, last) = s.splitAt(s.lastIndexOf('.') + 1)
    prefix + last.asSymbol
  }

  def fullScalaName(message: Descriptor): String =
    fullScalaName(message.getFullName, message.getFile)

  def fullScalaName(enum: EnumDescriptor): String =
    fullScalaName(enum.getFullName, enum.getFile)

  def fullScalaName(oneOf: OneofDescriptor): String =
    fullScalaName(oneOf.getContainingType) + "." + snakeCaseToCamelCase(oneOf.getName, true)

  def fullJavaName(message: Descriptor): String =
    fullJavaName(message.getFullName, message.getFile)

  def fullJavaName(enum: EnumDescriptor): String =
    fullJavaName(enum.getFullName, enum.getFile)

  private def allCapsToCamelCase(name: String, upperInitial: Boolean = false): String = {
    val b = new StringBuilder()
    @annotation.tailrec
    def inner(name: String, capNext: Boolean): Unit = if (name.nonEmpty) {
      val (r, capNext2) = name.head match {
        case c if c.isUpper =>
          // capitalize according to capNext.
          (Some(if (capNext) c else c.toLower), false)
        case c if c.isLower =>
          // Lower caps never get capitalized, but will force
          // the next letter to be upper case.
          (Some(c), true)
        case c if c.isDigit => (Some(c), true)
        case _ => (None, true)
      }
      r.foreach(b.append)
      inner(name.tail, capNext2)
    }
    inner(name, upperInitial)
    b.toString
  }

  private def snakeCaseToCamelCase(name: String, upperInitial: Boolean = false): String = {
    val b = new StringBuilder()
    @annotation.tailrec
    def inner(name: String, index: Int, capNext: Boolean): Unit = if (name.nonEmpty) {
      val (r, capNext2) = name.head match {
        case c if c.isLower => (Some(if (capNext) c.toUpper else c), false)
        case c if c.isUpper =>
          // force first letter to lower unless forced to capitalize it.
          (Some(if (index == 0 && !capNext) c.toLower else c), false)
        case c if c.isDigit => (Some(c), true)
        case _ => (None, true)
      }
      r.foreach(b.append)
      inner(name.tail, index + 1, capNext2)
    }
    inner(name, 0, upperInitial)
    b.toString
  }

  def baseName(fileName: String) =
    fileName.split("/").last.replaceAll(raw"[.]proto$$|[.]protodevel", "")

  def javaOuterClassName(file: FileDescriptor) =
    if (file.getOptions.hasJavaOuterClassname)
      file.getOptions.getJavaOuterClassname
    else {
      snakeCaseToCamelCase(baseName(file.getName), true)
    }

  def scalaOuterObjectName(file: FileDescriptor): String =
    javaOuterClassName(file) + "PB"

  def printEnum(e: EnumDescriptor, printer: FunctionalPrinter): FunctionalPrinter = {
    val javaName = fullJavaName(e)
    val name = e.getName.asSymbol
    printer
      .add(s"sealed trait $name extends com.trueaccord.scalapb.GeneratedEnum {")
      .indent
      .print(e.getValues) {
      case (v, p) => p.add(
        s"def is${allCapsToCamelCase(v.getName, true)}: Boolean = false")
    }
      .outdent
      .add("}")
      .add(s"object $name extends com.trueaccord.scalapb.GeneratedEnumCompanion[$name] {")
      .indent
      .print(e.getValues) {
      case (v, p) => p.addM(
        s"""case object ${v.getName.asSymbol} extends $name {
           |  val id = ${v.getNumber}
           |  val name = "${v.getName}"
           |  override def is${allCapsToCamelCase(v.getName, true)}: Boolean = true
           |}""")
    }
      .add(s"def fromValue(value: Int): $name = value match {")
      .print(e.getValues) {
      case (v, p) => p.add(s"  case ${v.getNumber} => ${v.getName.asSymbol}")
    }
      .addM(
        s"""}
           |def fromJavaValue(pbJavaSource: $javaName): $name = fromValue(pbJavaSource.getNumber)
           |def toJavaValue(pbScalaSource: $name): $javaName = $javaName.valueOf(pbScalaSource.id)
           |lazy val descriptor = new Descriptors.EnumDescriptor(${e.getIndex}, "${e.getName}", this)""")
      .outdent
      .add("}")
  }

  def printOneof(e: OneofDescriptor, printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"sealed trait ${e.upperScalaName} {")
      .indent
      .add(s"def isNotSet: Boolean = false")
      .print(e.fields) {
      case (v, p) => p
        .add(s"def is${v.upperScalaName}: Boolean = false")
    }
      .print(e.fields){
      case (v, p) => p
        .add(s"def `${v.scalaName}`: Option[${getScalaTypeName(v)}] = None")
    }
      .outdent
      .addM(
        s"""}
           |object ${e.upperScalaName} extends {
           |  case object NotSet extends ${e.upperScalaName} {
           |    override def isNotSet: Boolean = true
           |  }
         """)
      .indent
      .print(e.fields) {
      case (v, p) =>
        p.addM(
          s"""case class ${v.upperScalaName}(value: ${getScalaTypeName(v)}) extends ${e.upperScalaName} {
             |  override def is${v.upperScalaName}: Boolean = true
             |  override def `${v.scalaName}`: Option[${getScalaTypeName(v)}] = Some(value)
             |}""")
    }
      .add(s"implicit class ${e.upperScalaName}Lens[UpperPB](_l: com.trueaccord.lenses.Lens[UpperPB, ${e.upperScalaName}]) extends com.trueaccord.lenses.ObjectLens[UpperPB, ${e.upperScalaName}](_l) {")
      .indent
      .print(e.fields) {
      (field, printer) =>
        val fieldName = field.scalaName.asSymbol
        val getMethod = s"_.$fieldName.getOrElse(${defaultValueForGet(field)})"
        val typeName = getScalaTypeName(field.getContainingOneof)
        val boxedTypeName = s"$typeName.${field.upperScalaName}"
        printer
          .add(s"def $fieldName = field($getMethod)((c_, f_) => $boxedTypeName(f_))")
    }
      .outdent
      .add("}")
      .outdent
      .add("}")
  }

  def capitalizedType(fieldType: FieldDescriptor.Type) = fieldType match {
    case FieldDescriptor.Type.INT32 => "Int32"
    case FieldDescriptor.Type.UINT32  => "UInt32"
    case FieldDescriptor.Type.SINT32  => "SInt32"
    case FieldDescriptor.Type.FIXED32 => "Fixed32"
    case FieldDescriptor.Type.SFIXED32=> "SFixed32"
    case FieldDescriptor.Type.INT64   => "Int64"
    case FieldDescriptor.Type.UINT64  => "UInt64"
    case FieldDescriptor.Type.SINT64  => "SInt64"
    case FieldDescriptor.Type.FIXED64 => "Fixed64"
    case FieldDescriptor.Type.SFIXED64=> "SFixed64"
    case FieldDescriptor.Type.FLOAT   => "Float"
    case FieldDescriptor.Type.DOUBLE  => "Double"
    case FieldDescriptor.Type.BOOL    => "Bool"
    case FieldDescriptor.Type.STRING  => "String"
    case FieldDescriptor.Type.BYTES   => "Bytes"
    case FieldDescriptor.Type.ENUM    => "Enum"
    case FieldDescriptor.Type.GROUP   => "Group"
    case FieldDescriptor.Type.MESSAGE => "Message"
  }

  def fixedSize(fieldType: FieldDescriptor.Type): Option[Int] = fieldType match {
    case FieldDescriptor.Type.INT32 => None
    case FieldDescriptor.Type.UINT32  => None
    case FieldDescriptor.Type.SINT32  => None
    case FieldDescriptor.Type.FIXED32 => Some(4)
    case FieldDescriptor.Type.SFIXED32=> Some(4)
    case FieldDescriptor.Type.INT64   => None
    case FieldDescriptor.Type.UINT64  => None
    case FieldDescriptor.Type.SINT64  => None
    case FieldDescriptor.Type.FIXED64 => Some(8)
    case FieldDescriptor.Type.SFIXED64=> Some(8)
    case FieldDescriptor.Type.FLOAT   => Some(4)
    case FieldDescriptor.Type.DOUBLE  => Some(8)
    case FieldDescriptor.Type.BOOL    => Some(1)
    case FieldDescriptor.Type.STRING  => None
    case FieldDescriptor.Type.BYTES   => None
    case FieldDescriptor.Type.ENUM    => None
    case FieldDescriptor.Type.GROUP   => None
    case FieldDescriptor.Type.MESSAGE => None
  }

  def getScalaTypeName(oneOf: OneofDescriptor): String = {
    fullScalaName(oneOf)
  }

  def getScalaTypeName(descriptor: FieldDescriptor): String = {
    val base = descriptor.getJavaType match {
      case FieldDescriptor.JavaType.INT => "Int"
      case FieldDescriptor.JavaType.LONG => "Long"
      case FieldDescriptor.JavaType.FLOAT => "Float"
      case FieldDescriptor.JavaType.DOUBLE => "Double"
      case FieldDescriptor.JavaType.BOOLEAN => "Boolean"
      case FieldDescriptor.JavaType.BYTE_STRING => "com.google.protobuf.ByteString"
      case FieldDescriptor.JavaType.STRING => "String"
      case FieldDescriptor.JavaType.MESSAGE => fullScalaName(descriptor.getMessageType)
      case FieldDescriptor.JavaType.ENUM => fullScalaName(descriptor.getEnumType)
    }
    if (descriptor.isOptional && !descriptor.isInOneof) s"Option[$base]"
    else if (descriptor.isRepeated) s"Seq[$base]"
    else base
  }

  def escapeString(raw: String): String = {
    import scala.reflect.runtime.universe._
    Literal(Constant(raw)).toString
  }

  def byteArrayAsBase64Literal(buffer: Array[Byte]): String = {
    "\"\"\"" + new sun.misc.BASE64Encoder().encode(buffer) + "\"\"\""
  }

  def defaultValueForGet(field: FieldDescriptor) = {
    // Needs to be 'def' and not val since for some of the cases it's invalid to call it.
    def defaultValue = field.getDefaultValue
    field.getJavaType match {
      case FieldDescriptor.JavaType.INT => defaultValue.toString
      case FieldDescriptor.JavaType.LONG => defaultValue.toString + "L"
      case FieldDescriptor.JavaType.FLOAT =>
        val f = defaultValue.asInstanceOf[Float]
        if (f.isPosInfinity) "Float.PositiveInfinity"
        else if (f.isNegInfinity) "Float.NegativeInfinity"
        else f.toString + "f"
      case FieldDescriptor.JavaType.DOUBLE =>
        val d = defaultValue.asInstanceOf[Double]
        if (d.isPosInfinity) "Double.PositiveInfinity"
        else if (d.isNegInfinity) "Double.NegativeInfinity"
        else d.toString
      case FieldDescriptor.JavaType.BOOLEAN => Boolean.unbox(defaultValue.asInstanceOf[java.lang.Boolean])
      case FieldDescriptor.JavaType.BYTE_STRING => defaultValue.asInstanceOf[ByteString]
        .map(_.toString).mkString("com.google.protobuf.ByteString.copyFrom(Array[Byte](", ", ", "))")
      case FieldDescriptor.JavaType.STRING => escapeString(defaultValue.asInstanceOf[String])
      case FieldDescriptor.JavaType.MESSAGE =>
        fullScalaName(field.getMessageType) + ".defaultInstance"
      case FieldDescriptor.JavaType.ENUM =>
        fullScalaName(field.getEnumType) + "." + defaultValue.asInstanceOf[EnumValueDescriptor].getName.asSymbol
    }
  }

  def defaultValueForDefaultInstance(field: FieldDescriptor) =
    if (field.isOptional) "None"
    else if (field.isRepeated) "Nil"
    else defaultValueForGet(field)

  sealed trait ValueConversion
  case class ConversionMethod(name: String) extends ValueConversion
  case class ConversionFunction(name: String) extends ValueConversion
  case class BoxFunction(name: String) extends ValueConversion
  case object NoOp extends ValueConversion

  def javaFieldToScala(container: String, field: FieldDescriptor) = {
    val javaGetter = container + ".get" + field.upperScalaName
    val javaHas = container + ".has" + field.upperScalaName

    val valueConversion = field.getJavaType match {
      case FieldDescriptor.JavaType.INT => ConversionMethod("intValue")
      case FieldDescriptor.JavaType.LONG => ConversionMethod("longValue")
      case FieldDescriptor.JavaType.FLOAT => ConversionMethod("floatValue")
      case FieldDescriptor.JavaType.DOUBLE => ConversionMethod("doubleValue")
      case FieldDescriptor.JavaType.BOOLEAN => ConversionMethod("booleanValue")
      case FieldDescriptor.JavaType.BYTE_STRING => NoOp
      case FieldDescriptor.JavaType.STRING => NoOp
      case FieldDescriptor.JavaType.MESSAGE => ConversionFunction(
        fullScalaName(field.getMessageType) + ".fromJavaProto")
      case FieldDescriptor.JavaType.ENUM => ConversionFunction(
        fullScalaName(field.getEnumType) + ".fromJavaValue")
    }

    valueConversion match {
      case ConversionMethod(method) =>
        if (field.isRepeated) s"${javaGetter}List.map(_.$method)"
        else if (field.isOptional && !field.isInOneof) s"if ($javaHas) Some($javaGetter.$method) else None"
        else s"$javaGetter.$method"
      case ConversionFunction(func) =>
        if (field.isRepeated) s"${javaGetter}List.map($func)"
        else if (field.isOptional && !field.isInOneof) s"if ($javaHas) Some($func($javaGetter)) else None"
        else s"$func($javaGetter)"
      case BoxFunction(func) =>
        throw new RuntimeException("Unexpected method type")
      case NoOp =>
        if (field.isRepeated) s"${javaGetter}List.toSeq"
        else if (field.isOptional && !field.isInOneof) s"if ($javaHas) Some($javaGetter) else None"
        else javaGetter
    }
  }

  def assignScalaFieldToJava(scalaObject: String,
                             javaObject: String, field: FieldDescriptor): String = {
    val javaSetter = javaObject +
      (if (field.isRepeated) ".addAll" else ".set") + field.upperScalaName
    val scalaGetter = scalaObject + "." + fieldAccessorSymbol(field)

    val valueConversion = field.getJavaType match {
      case FieldDescriptor.JavaType.INT => BoxFunction("Int.box")
      case FieldDescriptor.JavaType.LONG => BoxFunction("Long.box")
      case FieldDescriptor.JavaType.FLOAT => BoxFunction("Float.box")
      case FieldDescriptor.JavaType.DOUBLE => BoxFunction("Double.box")
      case FieldDescriptor.JavaType.BOOLEAN => BoxFunction("Boolean.box")
      case FieldDescriptor.JavaType.BYTE_STRING => NoOp
      case FieldDescriptor.JavaType.STRING => NoOp
      case FieldDescriptor.JavaType.MESSAGE => ConversionFunction(
        fullScalaName(field.getMessageType) + ".toJavaProto")
      case FieldDescriptor.JavaType.ENUM => ConversionFunction(
        fullScalaName(field.getEnumType) + ".toJavaValue")
    }
    valueConversion match {
      case ConversionMethod(method) =>
        throw new RuntimeException("Unexpected method type")
      case ConversionFunction(func) =>
        if (field.isRepeated)
          s"$javaSetter($scalaGetter.map($func))"
        else if (field.isOptional)
          s"$scalaGetter.map($func).foreach($javaSetter)"
        else
          s"$javaSetter($func($scalaGetter))"
      case BoxFunction(func) if field.isRepeated =>
        s"$javaSetter($scalaGetter.map($func))"
      case NoOp | BoxFunction(_) =>
        if (field.isRepeated)
          s"$javaSetter($scalaGetter)"
        else if (field.isOptional)
          s"$scalaGetter.foreach($javaSetter)"
        else
          s"$javaSetter($scalaGetter)"
    }
  }

    def generateGetField(message: Descriptor)(fp: FunctionalPrinter) = {
      val signature = "def getField(field: Descriptors.FieldDescriptor): Any = "
      if (message.getFields.nonEmpty)
        fp.add(signature + "{")
          .indent
          .add("field.number match {")
          .indent
          .print(message.getFields) {
          case (f, fp) => fp.add(s"case ${f.getNumber} => ${fieldAccessorSymbol(f)}")
        }
          .outdent
          .add("}")
          .outdent
          .add("}")
      else fp.add(signature + "throw new MatchError(field)")
    }

  def generateWriteSingleValue(field: FieldDescriptor, valueExpr: String)(fp: FunctionalPrinter):
  FunctionalPrinter = {
    field.getType match {
      case FieldDescriptor.Type.MESSAGE =>
        fp.addM(
          s"""output.writeTag(${field.getNumber}, 2)
             |output.writeRawVarint32($valueExpr.serializedSize)
             |$valueExpr.writeTo(output)""")
      case FieldDescriptor.Type.ENUM =>
        fp.add(s"output.writeEnum(${field.getNumber}, $valueExpr.id)")
      case _ =>
        val capTypeName = capitalizedType(field.getType)
        fp.add(s"output.write$capTypeName(${field.getNumber}, $valueExpr)")
    }
  }

  def sizeExpressionForSingleField(field: FieldDescriptor, expr: String): String = field.getType match {
    case Type.MESSAGE =>
      CodedOutputStream.computeTagSize(field.getNumber) + s" + com.google.protobuf.CodedOutputStream.computeRawVarint32Size($expr.serializedSize) + $expr.serializedSize"
    case Type.ENUM => s"com.google.protobuf.CodedOutputStream.computeEnumSize(${field.getNumber}, ${expr}.id)"
    case t =>
      val capTypeName = capitalizedType(t)
      s"com.google.protobuf.CodedOutputStream.compute${capTypeName}Size(${field.getNumber}, ${expr})"
  }

  def fieldAccessorSymbol(field: FieldDescriptor) =
    if (field.isInOneof)
      (field.getContainingOneof.scalaName.asSymbol + "." +
        field.scalaName.asSymbol)
    else
      field.scalaName.asSymbol

  def generateSerializedSizeForField(field: FieldDescriptor, fp: FunctionalPrinter): FunctionalPrinter = {
    val fieldNameSymbol = fieldAccessorSymbol(field)
    if (field.isRequired) {
      fp.add("size += " + sizeExpressionForSingleField(field, fieldNameSymbol))
    } else if (field.isOptional) {
      fp.add(s"if ($fieldNameSymbol.isDefined) { size += ${sizeExpressionForSingleField(field, fieldNameSymbol + ".get")} }")
    } else if (field.isRepeated) {
      val tagSize = CodedOutputStream.computeTagSize(field.getNumber)
      if (!field.isPacked)
        fixedSize(field.getType) match {
          case Some(size) => fp.add(s"size += ${size + tagSize} * $fieldNameSymbol.size")
          case None => fp.add(
            s"$fieldNameSymbol.foreach($fieldNameSymbol => size += ${sizeExpressionForSingleField(field, fieldNameSymbol)})")
        }
      else {
        val fieldName = field.scalaName
        fp
          .addM(
            s"""if($fieldNameSymbol.nonEmpty) {
               |  size += $tagSize + com.google.protobuf.CodedOutputStream.computeRawVarint32Size(${fieldName}SerializedSize) + ${fieldName}SerializedSize
               |}""")
      }
    } else throw new RuntimeException("Should not reach here.")
  }

  def generateSerializedSize(message: Descriptor)(fp: FunctionalPrinter) = {
    fp
      .add("lazy val serializedSize: Int = {")
      .indent
      .add("var size = 0")
      .print(message.getFields)(generateSerializedSizeForField)
      .add("size")
      .outdent
      .add("}")
  }

  def generateSerializedSizeForPackedFields(message: Descriptor)(fp: FunctionalPrinter) =
    fp
      .print(message.getFields.filter(_.isPacked).zipWithIndex) {
      case ((field, index), printer) =>
        printer
          .add(s"lazy val ${field.scalaName}SerializedSize =")
          .call({ fp =>
          fixedSize(field.getType) match {
            case Some(size) =>
              fp.add(s"  $size * ${field.scalaName.asSymbol}.size")
            case None =>
              val capTypeName = capitalizedType(field.getType)
              fp.add(s"  ${field.scalaName.asSymbol}.map(com.google.protobuf.CodedOutputStream.compute${capTypeName}SizeNoTag).sum")
          }
        })
    }


  def generateWriteTo(message: Descriptor)(fp: FunctionalPrinter) =
    fp.add(s"def writeTo(output: com.google.protobuf.CodedOutputStream): Unit = {")
      .indent
      .print(message.getFields.sortBy(_.getNumber).zipWithIndex) {
      case ((field, index), printer) =>
        val fieldNameSymbol = fieldAccessorSymbol(field)
        val capTypeName = capitalizedType(field.getType)
        if (field.isPacked) {
            printer.addM(
              s"""if ($fieldNameSymbol.nonEmpty) {
                 |  output.writeTag(${field.getNumber}, 2)
                 |  output.writeRawVarint32(${fieldNameSymbol}SerializedSize)
                 |  ${fieldNameSymbol}.foreach(output.write${capTypeName}NoTag)
                 |}""")
        } else if (field.isRequired) {
          generateWriteSingleValue(field, fieldNameSymbol)(printer)
        } else {
          printer
            .add(s"${fieldNameSymbol}.foreach { v => ")
            .indent
            .call(generateWriteSingleValue(field, "v"))
            .outdent
            .add("}")
        }
    }
      .outdent
      .add("}")

  def printConstructorFieldList(message: Descriptor)(printer: FunctionalPrinter): FunctionalPrinter = {
    val regularFields = message.getFields.collect {
      case field if !field.isInOneof =>
      val typeName = getScalaTypeName(field)
      val ctorDefaultValue = if (field.isOptional) " = None"
          else if (field.isRepeated) " = Nil"
      else ""
        s"${field.scalaName.asSymbol}: $typeName$ctorDefaultValue"
    }
    val oneOfFields = message.getOneofs.map {
      oneOf =>
        val typeName = getScalaTypeName(oneOf)
        s"${oneOf.scalaName.asSymbol}: $typeName = $typeName.NotSet"
    }
    printer.addWithDelimiter(",")(regularFields ++ oneOfFields)
  }

  def generateMergeFrom(message: Descriptor)(printer: FunctionalPrinter): FunctionalPrinter = {
    val myFullScalaName = fullScalaName(message).asSymbol
    printer.add(
      s"def mergeFrom(input: com.google.protobuf.CodedInputStream): $myFullScalaName = {")
      .indent
      .print(message.fieldsWithoutOneofs)((field, printer) =>
        printer.add(s"var ${field.scalaName.asSymbol} = this.${field.scalaName.asSymbol}")
      )
      .print(message.getOneofs)((oneof, printer) =>
      printer.add(s"var ${oneof.scalaName.asSymbol} = this.${oneof.scalaName.asSymbol}")
      ).addM(
        s"""var __done = false
           |while (!__done) {
           |   input.readTag() match {
           |     case 0 => __done = true
           |   }
           |}""")
//      .print(message.getFields)((field, printer) =>
//        printer.add(s"case (${field.getNumber}, v_) => ")
//      )
      .add(s"$myFullScalaName(")
      .indent
      .addWithDelimiter(",")(
        (message.fieldsWithoutOneofs.map(_.scalaName.asSymbol) ++ message.getOneofs.map(_.scalaName.asSymbol))
          .map(sym => s"  $sym = $sym"))
      .outdent
      .add(")")
      .outdent
      .add("}")
  }

  def printMessage(message: Descriptor,
                   printer: FunctionalPrinter): FunctionalPrinter = {
    val className = message.getName.asSymbol

    val myFullScalaName = fullScalaName(message).asSymbol
    val myFullJavaName = fullJavaName(message)
    printer
      .add(s"case class $className(")
      .indent
      .indent
      .call(printConstructorFieldList(message))
      .add(s") extends com.trueaccord.scalapb.GeneratedMessage with com.trueaccord.lenses.Updatable[$className] {")
      .call(generateSerializedSizeForPackedFields(message))
      .call(generateSerializedSize(message))
      .call(generateWriteTo(message))
      .call(generateMergeFrom(message))
      .print(message.getFields) {
      case (field, printer) if !field.isInOneof =>
        val withMethod = "with" + field.upperScalaName
        val clearMethod = "clear" + field.upperScalaName
        val p0 = if (field.isOptional) {
          val getter = "get" + field.upperScalaName
          val default = defaultValueForGet(field)
          printer.add(s"def $getter = ${field.scalaName.asSymbol}.getOrElse($default)")
        } else printer
        val p1 = p0.add(s"def $withMethod(${field.scalaName.asSymbol}: ${getScalaTypeName(field)}) = copy(${field.scalaName.asSymbol} = ${field.scalaName.asSymbol})")
        if (field.isOptional || field.isRepeated)  {
          p1.add(s"def $clearMethod = copy(`${field.scalaName.asSymbol}` = ${if (field.isOptional) "None" else "Nil"})")
        } else p1
      case (field, printer) => printer
    }
      .call(generateGetField(message))
      .addM(
        s"""override def toString: String = com.google.protobuf.TextFormat.printToString($myFullScalaName.toJavaProto(this))
           |def companion = $myFullScalaName""")
      .outdent
      .outdent
      .addM(s"""}
               |
               |object $className extends com.trueaccord.scalapb.GeneratedMessageCompanion[$className] {""")
      .indent

      // toJavaProto
      .add(s"def toJavaProto(scalaPbSource: $myFullScalaName): $myFullJavaName = {")
      .indent
      .add(s"val javaPbOut = $myFullJavaName.newBuilder")
      .print(message.getFields) {
      case (field, printer) =>
        printer.add(assignScalaFieldToJava("scalaPbSource", "javaPbOut", field))
    }
      .add("javaPbOut.build")
      .outdent
      .add("}")

      // fromJavaProto
    .add(s"def fromJavaProto(javaPbSource: $myFullJavaName): $myFullScalaName = $myFullScalaName(")
      .indent
      .call {
      printer =>
        val normal = message.getFields.collect {
          case field if !field.isInOneof =>
            val conversion = javaFieldToScala("javaPbSource", field)
            s"${field.scalaName.asSymbol} = $conversion"
        }
        val oneOfs = message.getOneofs.map {
          case oneOf =>
            val oneOfTypeName = getScalaTypeName(oneOf)
            val javaEnumName = s"get${oneOf.upperScalaName}Case"
            val head = s"${oneOf.scalaName.asSymbol} = javaPbSource.$javaEnumName.getNumber match {"
            val body = oneOf.fields.map {
              field =>
                val t = s"$oneOfTypeName.${field.upperScalaName}"
                s"  case ${field.getNumber} => $t(${javaFieldToScala("javaPbSource", field)})"
            }
            val tail = Seq(s"  case _ => $oneOfTypeName.NotSet", "}")
            (Seq(head) ++ body ++ tail).mkString("\n")
        }
        printer.addWithDelimiter(",")(normal ++ oneOfs)
    }
      .outdent
      .add(")")

      // fromFieldsMap
      .add(s"def fromFieldsMap(fieldsMap: Map[Int, Any]): $myFullScalaName = $myFullScalaName(")
      .indent
      .call {
        printer =>
          val fields = message.getFields.collect {
            case field if !field.isInOneof =>
              val typeName = getScalaTypeName(field)
              val mapGetter = if (field.isOptional)
                s"fieldsMap.getOrElse(${field.getNumber}, None).asInstanceOf[$typeName]"
              else if (field.isRepeated)
                s"fieldsMap.getOrElse(${field.getNumber}, Nil).asInstanceOf[$typeName]"
              else
                s"fieldsMap(${field.getNumber}).asInstanceOf[$typeName]"
              s"${field.scalaName.asSymbol} = $mapGetter"
          }
          val oneOfs = message.getOneofs.map {
            oneOf =>
              val oneOfTypeName = getScalaTypeName(oneOf)
              val elems = oneOf.fields.map {
                field =>
                  val typeName = getScalaTypeName(field)
                  val t = s"$oneOfTypeName.${field.upperScalaName}"
                  s"fieldsMap.getOrElse(${field.getNumber}, None).asInstanceOf[Option[$typeName]].map(value => $t(value))"
              } mkString(" orElse\n")
              s"${oneOf.scalaName.asSymbol} = $elems getOrElse $oneOfTypeName.NotSet"
          }
          printer.addWithDelimiter(",")(fields ++ oneOfs)
    }
      .outdent
      .add(")")

      // fromAscii
      .addM(
        s"""override def fromAscii(ascii: String): $myFullScalaName = {
           |  val javaProtoBuilder = $myFullJavaName.newBuilder
           |  com.google.protobuf.TextFormat.merge(ascii, javaProtoBuilder)
           |  fromJavaProto(javaProtoBuilder.build)
           |}
           |lazy val descriptor = new Descriptors.MessageDescriptor("${message.getName}", this,
           |  None, m = Seq(${message.getNestedTypes.map(m => fullScalaName(m) + ".descriptor").mkString(", ")}),
           |  e = Seq(${message.getEnumTypes.map(m => fullScalaName(m) + ".descriptor").mkString(", ")}),
           |  f = ${scalaFullOuterObjectName(message.getFile)}.internalFieldsFor("${myFullScalaName}"))
           |lazy val defaultInstance = $myFullScalaName(""")
      .indent
      .addWithDelimiter(",")(message.getFields.collect {
      case field if field.isRequired =>
        val default = defaultValueForDefaultInstance(field)
        s"${field.scalaName.asSymbol} = $default"
    })
      .outdent
      .add(")")
      .print(message.getEnumTypes)(printEnum)
      .print(message.getOneofs)(printOneof)
      .print(message.getNestedTypes)(printMessage)
      .addM(
        s"""implicit def messageCompanion: com.trueaccord.scalapb.GeneratedMessageCompanion[$className] = this
           |implicit class ${className}Lens[UpperPB](_l: com.trueaccord.lenses.Lens[UpperPB, $className]) extends com.trueaccord.lenses.ObjectLens[UpperPB, $className](_l) {""")
      .indent
      .print(message.getFields) {
      case (field, printer) if !field.isInOneof =>
        val fieldName = field.scalaName.asSymbol
        if (field.isOptional) {
          val getMethod = "get" + field.upperScalaName
          val optionLensName = "optional" + field.upperScalaName
          printer
            .addM(s"""def $fieldName = field(_.$getMethod)((c_, f_) => c_.copy($fieldName = Some(f_)))
                     |def ${optionLensName} = field(_.$fieldName)((c_, f_) => c_.copy($fieldName = f_))""")
        } else
          printer.add(s"def $fieldName = field(_.$fieldName)((c_, f_) => c_.copy($fieldName = f_))")
      case (field, printer) => printer
    }
      .print(message.getOneofs) {
      case (oneof, printer) =>
        val oneofName = oneof.scalaName.asSymbol
          printer
            .add(s"def $oneofName = field(_.$oneofName)((c_, f_) => c_.copy($oneofName = f_))")
    }
      .outdent
      .add("}")
      .outdent
      .add("}")
      .add("")
      .add("")
  }

  def generateInternalFields(message: Descriptor, fp: FunctionalPrinter): FunctionalPrinter = {
    def makeDescriptor(field: FieldDescriptor): String = {
      val index = field.getIndex
      val label = if (field.isOptional) "Descriptors.Optional"
      else if (field.isRepeated) "Descriptors.Repeated"
      else if (field.isRequired) "Descriptors.Required"
      else throw new IllegalArgumentException()

      val t = field.getJavaType match {
        case t if field.getJavaType != JavaType.MESSAGE && field.getJavaType != JavaType.ENUM =>
          s"Descriptors.PrimitiveType(com.google.protobuf.Descriptors.FieldDescriptor.JavaType.$t, " +
          s"com.google.protobuf.Descriptors.FieldDescriptor.Type.${field.getType})"
        case JavaType.MESSAGE => "Descriptors.MessageType(" + fullScalaName(field.getMessageType) + ".descriptor)"
        case JavaType.ENUM => "Descriptors.EnumType(" + fullScalaName(field.getEnumType) + ".descriptor)"
      }
      val oneof = field.containingOneOf.map(s => s"""Some("$s")""").getOrElse("None")
      s"""Descriptors.FieldDescriptor($index, ${field.getNumber}, "${field.getName}", $label, $t, isPacked = ${field.isPacked}, containingOneofName = $oneof)"""
    }

    fp.add(s"""case "${fullScalaName(message)}" => Seq(${message.getFields.map(makeDescriptor).mkString(", ")})""")
      .print(message.getNestedTypes)(generateInternalFields)
  }

  def generateInternalFieldsFor(file: FileDescriptor)(fp: FunctionalPrinter): FunctionalPrinter =
    if (file.getMessageTypes.nonEmpty) {
      fp.add("def internalFieldsFor(scalaName: String): Seq[Descriptors.FieldDescriptor] = scalaName match {")
        .indent
        .print(file.getMessageTypes)(generateInternalFields)
        .outdent
        .add("}")
    } else fp

  def generateFile(file: FileDescriptor): CodeGeneratorResponse.File = {
    val b = CodeGeneratorResponse.File.newBuilder()

    b.setName(javaPackage(file).replace('.', '/') + "/" + scalaOuterObjectName(file) + ".scala")
    val p0 = new FunctionalPrinter()

    val p1 = p0.add(
      "// Generated by the Scala Plugin for the Protocol Buffer Compiler.",
      "// Do not edit!",
      "",
      if (javaPackage(file).nonEmpty) s"package ${javaPackage(file)}" else "",
      "import scala.collection.JavaConversions._",
      "import com.trueaccord.scalapb.Descriptors",
      "",
      s"object ${scalaOuterObjectName(file)} {")
      .indent
      .print(file.getEnumTypes)(printEnum)
      .print(file.getMessageTypes)(printMessage)
      .call(generateInternalFieldsFor(file))
      .outdent
      .add("}")
    b.setContent(p1.toString)
    b.build
  }
}
