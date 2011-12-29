package scales.xml

import scales.utils._

import scala.collection.immutable.{ Stack, Map }

import java.io.Writer

import java.nio.charset.Charset

import serializers._

/**
 * Defaults to correctness and checking everything
 * Possible approach to easily configuring some performance aspects
 * I.e. the are really sure that after the 100th time of sending the same
 * message template they don't need to verify the QNames again.
 *
 * case class SerializationOptions(
 * checkCDataEncoding : Boolean = true,
 * checkCommentEncoding : Boolean = true,
 * checkPIEncoding : Boolean = true,
 * checkNSDeclPrefix : Boolean = true,
 * checkNSDeclPrefixEncoding : Boolean = true,
 * check
 * )
 */

import serializers._

/**
 * This class represents state during a serialization
 */
case class XmlOutput(data: SerializerData,
  currentMappings: Stack[Map[String, String]] = Stack[Map[String, String]]().push(
    Map[String, String]() + ("" -> "") // default namespace 
    ), path: List[QName] = List())(implicit serializerFI: SerializerFactory) {
  val serializerF = serializerFI
}

trait XmlPrinterImplicits {

  /**
   * Import to _ and replace with your own SerializerFactory if desired
   */
  implicit val defaultSerializerFactory = serializers.LSSerializerFactory

  case class DeclarationConverter(decl: Declaration) {
    def withWriter(out: Writer): SerializerData = scales.xml.withWriter(decl, out)
  }

  implicit def toWithWriter(decl: Declaration) = DeclarationConverter(decl)

}

trait XmlPrinter {
  import ScalesXml._

  /**
   * a) add / replace in mappings
   * b) add to declMap
   */
  def laddNS(entry: (String, String), mappings: Map[String, String], declMap: Map[String, String]) = {
    val mapping = mappings.get(entry._1)

    if (mapping.isDefined) {
      // currently exists
      if (mapping.get == entry._2) {
        // do nothing
        (mappings, declMap)
      } else {
        // replace and redefine
        (mappings.updated(entry._1, entry._2),
          declMap + entry)
      }
    } else {
      // map and define
      (mappings + (entry._1 -> entry._2),
        declMap + entry)
    }
  }

  def doElement(x: Elem, out: XmlOutput) = {

    // for each of our namespace mappings that differ (or are not there)
    var (mappings, declMap) = x.namespaces.foldLeft( // (nsmappings start with top , what we declare)
      (out.currentMappings.top, Map[String, String]()))((p, a) => laddNS(a, p._1, p._2))

    // with our attributes
    val t = x.attributes.foldLeft((mappings, declMap)) { (p, a) =>
      val e = a
      if (e.prefix.isDefined)
        laddNS((e.prefix.get, e.namespace.uri), p._1, p._2)
      else p
    }

    mappings = t._1
    declMap = t._2

    // is this namespace declared?
    val addedDefaultNS = {
      val currentDefault = mappings(Default.namespace.uri)
      if (x.name.namespace != Default.noNamespace) {
        // is there a prefix?
        if (x.name.prefix.isDefined) {
          // then it is a normal laddNS
          val (imappings, ideclMap) = laddNS((x.name.prefix.get, x.name.namespace.uri), mappings, declMap)
          mappings = imappings
          declMap = ideclMap
          // push the current on again
          false
        } else {
          // there will always be on as we don't remove only redefine.

          // is it the current default namespace? 
          if (currentDefault == x.name.namespace.uri) {
            // keep the current
            false
          } else {
            // must redefine it
            // then the namespace has been badly declared as we have a conflict, but in order for at
            // least this element to be valid we must remap it, log it?
            mappings = mappings.updated(Default.namespace.uri, x.name.namespace.uri)
            true
          }
        }
      } else {
        // then if the current default is not "" we must redeclare it
        if (currentDefault == Default.noNamespace.uri) {
          false
        } else {
          mappings = mappings.updated(Default.namespace.uri, Default.noNamespace.uri)
          true
        }
      }
    }

    // don't render it here, mappings contains it
    declMap = declMap - Default.namespace.uri

    val default = x.namespaces.get(Default.namespace.uri)
    val addDef =
      if (addedDefaultNS || default.isDefined) {
        // we have a new mapping, old one doesn't work anymore
        Some(mappings(Default.namespace.uri))
      } else
        None

    (mappings, (x.attributes, declMap, addDef))
  }

  def withWriter(decl: Declaration, out: Writer): SerializerData = SerializerData(out, decl.version, decl.encoding)

  protected def serializeMisc(pout: XmlOutput, misc: Iterable[Misc], serializer: Serializer) = {
    val opt = misc.foldLeft(None: Option[Throwable]) { (x, m) =>
      x.orElse {
        serializer.item(m.fold[XmlItem](z => z, y => y), List())
      }
    }
    (pout, opt)
  }

  def headerAndFooter(pout: XmlOutput, doc: DocLike)(serializerf: (XmlOutput, Serializer) => (XmlOutput, Option[Throwable])): Serializer => Option[Throwable] = { serializer =>

    var out = pout
    // TODO DTD and header
    serializer.xmlDeclaration(pout.data.encoding, pout.data.version).orElse {
      // DTD
      None
    }.orElse {
      val (pout, opt) = serializeMisc(out, doc.prolog.misc, serializer)
      out = pout
      opt
    }.orElse {
      val (pout, opt) = serializerf(out, serializer)
      out = pout
      opt
    }.orElse {
      val (pout, opt) = serializeMisc(out, doc.end.misc, serializer)
      out = pout
      opt
    }
  }

  /**
   * Type class for choosing a serializing algo
   */
  trait SerializeableXml[T] {

    def doc(it: T): DocLike

    def apply(it: T)(out: XmlOutput, serializer: Serializer): (XmlOutput, Option[Throwable])
  }

  type CloseablePull = XmlPull with java.io.Closeable with scales.utils.IsClosed

  /**
   * Placeholder - prefer serialize instead
   */
  def foldPrint[T: SerializeableXml](pout: XmlOutput)(it: T) =
    serialize(pout)(it)

  /**
   * Serializes items which can behave like xml.
   */
  def serialize[T: SerializeableXml](pout: XmlOutput)(it: T) =
    pout.serializerF {
      val sxml = implicitly[SerializeableXml[T]]
      headerAndFooter(pout, sxml.doc(it))(
        sxml(it))
    }(pout.data)

  /**
   * Prints to stdout, useful for testing etc  Will dump an error if one is found.
   *
   * Note it outputs to the vmDefaultCharset so it should always be C+P able
   */
  def printTree[T](xml: T)(implicit serf: SerializerFactory, sxml: SerializeableXml[T]): Unit = {
    val out = new java.io.PrintWriter(System.out)
    serialize(XmlOutput(SerializerData(out, encoding = vmDefaultCharset)))(xml) foreach { e =>
      out.println("Could not serialize got the following exception " + e.getClass.getName + " - " + e.getMessage)
      e.printStackTrace(out)
    }
    out.flush
    //out.close, v bad to close stdout :-)
    println()
  }

  /**
   * Returns a string version of the tree or throws
   */
  def asString[T](xml: T)(implicit serf: SerializerFactory, sxml: SerializeableXml[T]): String = {
    val builder = new java.io.StringWriter()
    foldPrint(XmlOutput(SerializerData(builder)))(xml) foreach {
      throw _
    }
    builder.toString
  }

  /**
   * Just for items, given we don't want to generally serialize them directly but as part of a tree.  Useful for debugging only
   */ 
  def itemAsString(xmlItem: XmlItem)(implicit serf: SerializerFactory): String = {
    implicit val items = new SerializeableXml[XmlItem] {
      def doc(it: XmlItem) = EmptyDoc()
      def apply(it: XmlItem)(out: XmlOutput, serializer: Serializer): (XmlOutput, Option[Throwable]) = (out, serializer.item(it, out.path))
    }
    
    val builder = new java.io.StringWriter()
    val o = XmlOutput(SerializerData(builder))
    serf{ s =>
      s.item(xmlItem, o.path)
    }(o.data)
//    foldPrint(XmlOutput(SerializerData(builder)))(xml) foreach {
  //    throw _
    //}
    builder.toString
  }
}