package avrohugger
import specific._
import methods._

import treehugger.forest._
import definitions._
import treehuggerDSL._

import org.apache.avro.Schema

import java.nio.file.{Path, Paths, Files, StandardOpenOption}
import java.io.{File, FileNotFoundException, IOException}
import scala.collection.JavaConversions._

object SpecificAvroHugger {

  def write(
    classStore: ClassStore, 
    namespace: Option[String], 
    schema: Schema, 
    outDir: String): Unit = {

    // register new type
    val classSymbol = RootClass.newClass(schema.getName)
    classStore.accept(schema, classSymbol)

    // generate list of constructor parameters
    val params: List[ValDef] = schema.getFields.toList.map { field =>
      val fieldName = field.name
      val fieldType = TypeMatcher.toType(classStore, namespace, field.schema)
      VAR(fieldName, fieldType): ValDef 
    }
 
    // generate class definition 
    val classDef = {

      // extension
      val baseClass = RootClass.newClass("org.apache.avro.specific.SpecificRecordBase")

      // no-arg constructor
      val defaultParams: List[Tree] = schema.getFields.toList.map(avroField => DefaultParamMatcher.asDefaultParam(classStore, avroField.schema))
      val defThis = DEFTHIS.withParams(PARAM("")).tree := THIS APPLY(defaultParams:_*)

      // methods - first add an index the the fields
      val indexedFields = schema.getFields.toList.zipWithIndex.map(p => IndexedField(p._1, p._2))
      val defGet = GetGenerator.toDef(indexedFields)
      val defPut = PutGenerator.toDef(classStore, namespace, indexedFields) 
      val defGetSchema = GetSchemaGenerator(classSymbol).toDef 

      // define the class def with the members previously defined
      CASECLASSDEF(classSymbol).withParams(params).withParents(baseClass) := BLOCK( 
        defThis,
        defGet,
        defPut,
        defGetSchema
      )
    }

    // generate companion object definition
    val objectDef = {

      // register avro class
      val ParserClass = RootClass.newClass("org.apache.avro.Schema.Parser")

      // new val 
      val valSchema = VAL(REF("SCHEMA$")) := (NEW(ParserClass)) APPLY() DOT "parse" APPLY(LIT(schema.toString))

      // companion object definion
      OBJECTDEF(classSymbol) := BLOCK( 
        valSchema
      )
 
    }

    // wrap the class definition in a block with a comment and a package
    val tree = {
      if (namespace.isDefined) BLOCK(classDef, objectDef).inPackage(namespace.get)
      else BLOCK(classDef, objectDef).withoutPackage
    }.withDoc("MACHINE-GENERATED FROM AVRO SCHEMA. DO NOT EDIT DIRECTLY")

    //write the tree
    writeToFile(namespace, schema, tree, outDir)
  }
  
  // write the definition to file
  private def writeToFile(namespace: Option[String], schema: Schema, tree: Tree, outDir: String): Unit = {
    val folderPath: Path = Paths.get{
      if (namespace.isDefined) outDir + "/" + namespace.get.toString.replace('.','/')
      else outDir
    }
    if (!Files.exists(folderPath)) Files.createDirectories(folderPath)
    val filePath = { 
      Paths.get(folderPath.toString + "/" + schema.getName + ".scala")
    }
    try { // delete old and/or create new
      Files.deleteIfExists(filePath)
      Files.write(filePath, treeToString(tree).getBytes(),  StandardOpenOption.CREATE) 
      () 
    }//finally pw.close()     
    catch {
      case ex: FileNotFoundException => sys.error("File not found:" + ex)
      case ex: IOException => sys.error("There was a problem using the file: " + ex)
    }
  }
}