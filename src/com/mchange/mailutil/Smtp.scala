package com.mchange.mailutil

import java.util.{Date,Properties}
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.{Authenticator, PasswordAuthentication, Session, Transport}
import java.io.{BufferedInputStream,FileNotFoundException}
import scala.util.{Try,Failure,Success,Using}
import scala.jdk.CollectionConverters.*
import scala.io.Codec
import scala.collection.immutable
import com.mchange.conveniences.javautil.loadProperties

object Smtp:
  private def check( strict : Boolean )( addresses : Seq[Address] ) : Seq[Address] =
    if strict then
      try
        addresses.foreach( a => a.toInternetAddress.validate() )
      catch
        case ae : AddressException =>
          throw new SmtpAddressParseFailed( ae.getMessage(), ae )
    addresses

  object Env:
    val Host = "SMTP_HOST"
    val Port = "SMTP_PORT"
    val User = "SMTP_USER"
    val Password = "SMTP_PASSWORD"
    val StartTls = "SMTP_STARTTLS"
    val StartTlsAlt = "SMTP_START_TLS"
    val Debug = "SMTP_DEBUG"
    val Properties = "SMTP_PROPERTIES" // path to a properties file containing the properties below
  object Prop:
    val Host = "mail.smtp.host"
    val Port = "mail.smtp.port"
    val Auth = "mail.smtp.auth"
    val User = "mail.smtp.user"
    val Password = "mail.smtp.password" // this is NOT a standard javax.mail property AFAIK
    val StartTlsEnable = "mail.smtp.starttls.enable"
    val Debug = "mail.smtp.debug"
    object SyspropOnly:
      val Properties = "mail.smtp.properties"
  object Port:
    // see e.g. https://sendgrid.com/blog/whats-the-difference-between-ports-465-and-587/
    val Vanilla     = 25
    val ImplicitTls = 465
    val StartTls    = 587
  object Address:
    def fromInternetAddress( iaddress : InternetAddress ) : Address = Address( iaddress.getAddress(), Option(iaddress.getPersonal()) )
    def parseCommaSeparated( line : String, strict : Boolean = true ) : Seq[Address] =
      check(strict)( immutable.ArraySeq.unsafeWrapArray( InternetAddress.parse(line).map( fromInternetAddress ) ) )
    def parseSingle( fullAddress : String, strict : Boolean = true ) : Address =
      val out = parseCommaSeparated( fullAddress, strict )
      out.size match
        case 0 => throw new SmtpAddressParseFailed("Expected to parse one valid SMTP address, none found.")
        case 1 => out.head
        case n => throw new SmtpAddressParseFailed(s"Expected to parse one valid SMTP address, ${n} found.")
  case class Address( email : String, displayName : Option[String] = None, codec : Codec = Codec.UTF8):
    lazy val toInternetAddress = new InternetAddress( email, displayName.getOrElse(null), codec.charSet.name() )
  case class Auth( user : String, password : String ) extends Authenticator:
    override def getPasswordAuthentication() : PasswordAuthentication = new PasswordAuthentication(user, password)
  object Context:
    lazy given TrySmtpContext : Try[Context] = Try( Smtp.Context.Default )
    lazy given Default : Context = this.apply( defaultProperties(), sys.env )
    private def findProperties( explicitSourceIfAny : Option[os.Path] = None ) : Properties =
        (explicitSourceIfAny.map( _.toString) orElse sys.props.get(Prop.SyspropOnly.Properties) orElse sys.env.get(Env.Properties)) match
          case Some( pathStr ) =>
            val path = os.Path(pathStr)
            val tryProps = Try:
              if os.exists(path) then
                Using.resource(new BufferedInputStream(os.read.inputStream(path))): is =>
                  val props = new Properties( System.getProperties )
                  props.load(is)
                  props
              else
                System.err.println(s"[SMTP config] No file at $path (perhaps specified by environment variable '${Env.Properties}'). Reverting to system properties only.")
                System.getProperties
            tryProps match
              case Success( props ) => props
              case Failure( t ) =>
                System.err.println(s"[SMTP config] Reverting to system properties only. Exception while loading SMTP properties at path specified by ${Env.Properties}, ${path}:")
                t.printStackTrace()
                System.getProperties
          case None =>
            System.getProperties
    def defaultProperties() = findProperties(None)
    def loadPropertiesWithDefaults( explicitSource : os.Path, requirePresent : Boolean = true ) : Properties =
      val defaults = defaultProperties()
      if os.exists( explicitSource ) then
        loadProperties( explicitSource.toIO, Some( defaults ) )
      else  
        if !requirePresent then
          System.err.println(s"[SMTP config] No file at specified $explicitSource. Reverting to default configuration strategy only.")
          defaults
        else
          throw new FileNotFoundException( s"Properties file '${explicitSource}' required, not found." )
    def apply( properties : Properties, environment : Map[String,String]) : Context =
      val propsMap = properties.asScala
      val host = (propsMap.get(Prop.Host) orElse environment.get(Env.Host)).map(_.trim).getOrElse( throw new SmtpInitializationFailed("No SMTP Host Configured") )
      val mbUser = (propsMap.get(Prop.User) orElse environment.get(Env.User)).map(_.trim)
      val mbPassword = (propsMap.get(Prop.Password) orElse environment.get(Env.Password)).map(_.trim)
      val auth =
        val mbFlagConfigured = propsMap.get(Prop.Auth)
        (mbFlagConfigured, mbUser, mbPassword) match
          case (Some("true") , Some(user), Some(password)) => Some(Auth(user, password))
          case (None         , Some(user), Some(password)) => Some(Auth(user, password))
          case (Some("false"), Some(_),Some(_)) =>
            System.err.println(s"WARNING: SMTP user and password are both configured, but property '${Prop.Auth}' is false, so authentication is disabled.")
            None
          case (Some("false"), _, _) => None
          case (Some(bad), Some(user), Some(password)) =>
            System.err.println(s"WARNING: Ignoring bad SMTP property '${Prop.Auth}' set to '${bad}'. User and password are set so authentication is enabled.")
            Some(Auth(user, password))
          case (None, Some(user), None) =>
            System.err.println(s"WARNING: A user '${user}' is configured, but no password is set, so authentication is disabled.")
            None
          case (_, _, _) =>
            None
      val specifiedPort : Option[Int] = (propsMap.get(Prop.Port) orElse environment.get(Env.Port)).map( _.toInt )

      val startTlsEnabled =
        (propsMap.get(Prop.StartTlsEnable) orElse environment.get(Env.StartTls) orElse environment.get(Env.StartTlsAlt))
          .map(_.toBoolean)
          .getOrElse( specifiedPort == Some(Port.StartTls) )

      // XXX: Can there be unauthenticated TLS? I'm presuming authentication suggests one for or another of TLS
      def defaultPort = auth.fold(Port.Vanilla)(_ => if startTlsEnabled then Port.StartTls else Port.ImplicitTls)

      val port = specifiedPort.getOrElse( defaultPort )
      val debug = (propsMap.get(Prop.Debug) orElse environment.get(Env.Debug)).map(_.toBoolean).getOrElse(false)
      Context(host,port,auth,startTlsEnabled,debug)
    end apply
  end Context
  case class Context(
    host : String,
    port : Int = 25,
    auth : Option[Auth] = None,
    startTls : Boolean = false,
    debug    : Boolean = false
  ):
    lazy val props =
      val tmp = new Properties()
      tmp.setProperty(Prop.Host,           this.host)
      tmp.setProperty(Prop.Port,           this.port.toString)
      tmp.setProperty(Prop.Auth,           this.auth.nonEmpty.toString)
      tmp.setProperty(Prop.StartTlsEnable, this.startTls.toString)
      tmp.setProperty(Prop.Debug,          this.debug.toString)
      tmp

    lazy val session =
      val tmp = Session.getInstance(props, this.auth.getOrElse(null))
      tmp.setDebug(this.debug)
      tmp

    private def sendUnauthenticated( msg : MimeMessage ) : Unit = // untested
      // Transport.send(msg)
      Using.resource(session.getTransport("smtp")): transport =>
        transport.connect(this.host, this.port, null, null)
        transport.sendMessage(msg, msg.getAllRecipients())

    private def sendAuthenticated( msg : MimeMessage, auth : Auth ) : Unit =
      Using.resource(session.getTransport("smtps")): transport =>
        transport.connect(this.host, this.port, auth.user, auth.password);
        transport.sendMessage(msg, msg.getAllRecipients());

    def sendMessage( msg : MimeMessage ) =
      this.auth.fold(sendUnauthenticated(msg))(auth => sendAuthenticated(msg,auth))
  end Context
  object AddressesRep:
    given AddressesRep[String] with
      def toAddresses( src : String, strict : Boolean ) : Seq[Address] =
        if src.isEmpty then Seq.empty else Address.parseCommaSeparated(src, strict)
    given AddressesRep[Address] with
      def toAddresses( src : Address, strict : Boolean ) : Seq[Address] =
        check(strict)( Seq(src) )
    given AddressesRep[Seq[Address]] with
      def toAddresses( src : Seq[Address], strict : Boolean ) : Seq[Address] =
        check(strict)( src )
    given AddressesRepSeqString : AddressesRep[Seq[String]] with // named, given if left anonymous, generated name conflicted with Seq[Address] instance
      def toAddresses( src : Seq[String], strict : Boolean ) : Seq[Address] =
        src.flatMap( s => Address.parseCommaSeparated(s, strict) )
  trait AddressesRep[A]:
    def toAddresses( src : A, strict : Boolean ) : Seq[Address]
  end AddressesRep
  
  private def setSubjectFromToCcBccReplyTo(
    msg     : MimeMessage,
    subject : String,
    from    : Seq[Address],
    to      : Seq[Address],
    cc      : Seq[Address],
    bcc     : Seq[Address],
    replyTo : Seq[Address]
  )( using context : Smtp.Context ) : Unit =
    msg.setSubject(subject)
    if from.nonEmpty then
      msg.addFrom( from.map( _.toInternetAddress ).toArray )
    to.foreach( address => msg.addRecipient(Message.RecipientType.TO, address.toInternetAddress) )
    cc.foreach( address => msg.addRecipient(Message.RecipientType.CC, address.toInternetAddress) )
    bcc.foreach( address => msg.addRecipient(Message.RecipientType.BCC, address.toInternetAddress) )
    if replyTo.nonEmpty then
      msg.setReplyTo( replyTo.map( _.toInternetAddress ).toArray )
  end setSubjectFromToCcBccReplyTo

  /**
    * Does not set `msg.setSentDate(...)`.
    * Be sure to do so, and then `msg.saveChanges()` before sending!
    */
  def _composeSimple( mimeType : String )(
    contents  : Object,
    subject   : String,
    from      : Seq[Address],
    to        : Seq[Address],
    cc        : Seq[Address],
    bcc       : Seq[Address],
    replyTo   : Seq[Address]
  )( using context : Smtp.Context ) : MimeMessage =
    val msg = new MimeMessage(context.session)
    msg.setContent(contents, mimeType)
    setSubjectFromToCcBccReplyTo( msg, subject, from, to, cc, bcc, replyTo )
    msg.saveChanges()
    msg
  end _composeSimple

  /**
    * Does not set `msg.setSentDate(...)`.
    * Be sure to do so, and then `msg.saveChanges()` before sending!
    */
  def composeSimple[A:AddressesRep,B:AddressesRep,C:AddressesRep,D:AddressesRep,E:AddressesRep]( mimeType : String )(
    contents  : Object,
    subject   : String,
    from      : A,
    to        : B,
    cc        : C = Seq.empty[Address],
    bcc       : D = Seq.empty[Address],
    replyTo   : E = Seq.empty[Address],
    strict    : Boolean = true
  )( using context : Smtp.Context ) : MimeMessage =
    _composeSimple( mimeType )(
      contents,
      subject,
      summon[AddressesRep[A]].toAddresses(from, strict),
      summon[AddressesRep[B]].toAddresses(to, strict),
      summon[AddressesRep[C]].toAddresses(cc, strict),
      summon[AddressesRep[D]].toAddresses(bcc, strict),
      summon[AddressesRep[E]].toAddresses(replyTo, strict),
    )
  end composeSimple

  def _sendSimple( mimeType : String )(
    contents  : Object,
    subject   : String,
    from      : Seq[Address],
    to        : Seq[Address],
    cc        : Seq[Address],
    bcc       : Seq[Address],
    replyTo   : Seq[Address]
  )( using context : Smtp.Context ) : Unit =
    val msg = _composeSimple( mimeType )( contents, subject, from, to, cc, bcc, replyTo )
    msg.setSentDate(new Date())
    msg.saveChanges()
    context.sendMessage(msg)
  end _sendSimple

  def sendSimple[A:AddressesRep,B:AddressesRep,C:AddressesRep,D:AddressesRep,E:AddressesRep]( mimeType : String )(
    contents  : Object,
    subject   : String,
    from      : A,
    to        : B,
    cc        : C = Seq.empty[Address],
    bcc       : D = Seq.empty[Address],
    replyTo   : E = Seq.empty[Address],
    strict    : Boolean = true
  )( using context : Smtp.Context ) : Unit =
    _sendSimple( mimeType )(
      contents,
      subject,
      summon[AddressesRep[A]].toAddresses(from, strict),
      summon[AddressesRep[B]].toAddresses(to, strict),
      summon[AddressesRep[C]].toAddresses(cc, strict),
      summon[AddressesRep[D]].toAddresses(bcc, strict),
      summon[AddressesRep[E]].toAddresses(replyTo, strict)
    )
  end sendSimple

  /**
    * Does not set `msg.setSentDate(...)`.
    * Be sure to do so, and then `msg.saveChanges()` before sending!
    */
  def composeSimplePlaintext[A:AddressesRep,B:AddressesRep,C:AddressesRep,D:AddressesRep,E:AddressesRep](
    plaintext : String,
    subject   : String,
    from      : A,
    to        : B,
    cc        : C = Seq.empty[Address],
    bcc       : D = Seq.empty[Address],
    replyTo   : E = Seq.empty[Address],
    strict    : Boolean = true
  )( using context : Smtp.Context ) : MimeMessage =
    _composeSimple("text/plain")(
      plaintext,
      subject,
      summon[AddressesRep[A]].toAddresses(from, strict),
      summon[AddressesRep[B]].toAddresses(to, strict),
      summon[AddressesRep[C]].toAddresses(cc, strict),
      summon[AddressesRep[D]].toAddresses(bcc, strict),
      summon[AddressesRep[E]].toAddresses(replyTo, strict),
    )
  end composeSimplePlaintext

  def sendSimplePlaintext[A:AddressesRep,B:AddressesRep,C:AddressesRep,D:AddressesRep,E:AddressesRep](
    plaintext : String,
    subject   : String,
    from      : A,
    to        : B,
    cc        : C = Seq.empty[Address],
    bcc       : D = Seq.empty[Address],
    replyTo   : E = Seq.empty[Address],
    strict    : Boolean = true
  )( using context : Smtp.Context ) : Unit =
    _sendSimple("text/plain")(
      plaintext,
      subject,
      summon[AddressesRep[A]].toAddresses(from, strict),
      summon[AddressesRep[B]].toAddresses(to, strict),
      summon[AddressesRep[C]].toAddresses(cc, strict),
      summon[AddressesRep[D]].toAddresses(bcc, strict),
      summon[AddressesRep[E]].toAddresses(replyTo, strict)
    )
  end sendSimplePlaintext

  /**
    * Does not set `msg.setSentDate(...)`.
    * Be sure to do so, and then `msg.saveChanges()` before sending!
    */
  def composeSimpleHtmlOnly[A:AddressesRep,B:AddressesRep,C:AddressesRep,D:AddressesRep,E:AddressesRep](
    html      : String,
    subject   : String,
    from      : A,
    to        : B,
    cc        : C = Seq.empty[Address],
    bcc       : D = Seq.empty[Address],
    replyTo   : E = Seq.empty[Address],
    strict    : Boolean = true
  )( using context : Smtp.Context ) : MimeMessage =
    _composeSimple("text/html")(
      html,
      subject,
      summon[AddressesRep[A]].toAddresses(from, strict),
      summon[AddressesRep[B]].toAddresses(to, strict),
      summon[AddressesRep[C]].toAddresses(cc, strict),
      summon[AddressesRep[D]].toAddresses(bcc, strict),
      summon[AddressesRep[E]].toAddresses(replyTo, strict),
    )
  end composeSimpleHtmlOnly

  def sendSimpleHtmlOnly[A:AddressesRep,B:AddressesRep,C:AddressesRep,D:AddressesRep,E:AddressesRep](
    html      : String,
    subject   : String,
    from      : A,
    to        : B,
    cc        : C = Seq.empty[Address],
    bcc       : D = Seq.empty[Address],
    replyTo   : E = Seq.empty[Address],
    strict    : Boolean = true
  )( using context : Smtp.Context ) : Unit =
    _sendSimple("text/html")(
      html,
      subject,
      summon[AddressesRep[A]].toAddresses(from, strict),
      summon[AddressesRep[B]].toAddresses(to, strict),
      summon[AddressesRep[C]].toAddresses(cc, strict),
      summon[AddressesRep[D]].toAddresses(bcc, strict),
      summon[AddressesRep[E]].toAddresses(replyTo, strict)
    )
  end sendSimpleHtmlOnly

  /**
    * Does not set `msg.setSentDate(...)`.
    * Be sure to do so, and then `msg.saveChanges()` before sending!
    */
  def _composeSimpleHtmlPlaintextAlternative(
    html      : String,
    plaintext : String,
    subject   : String,
    from      : Seq[Address],
    to        : Seq[Address],
    cc        : Seq[Address],
    bcc       : Seq[Address],
    replyTo   : Seq[Address]
  )( using context : Smtp.Context ) : MimeMessage =
    val msg = new MimeMessage(context.session)
    val htmlAlternative =
      val tmp = new MimeBodyPart()
      tmp.setContent(html, "text/html")
      tmp
    val plaintextAlternative =
      val tmp = new MimeBodyPart()
      tmp.setContent(plaintext, "text/plain")
      tmp
    // last entry is highest priority!
    val multipart = new MimeMultipart("alternative", plaintextAlternative, htmlAlternative)
    msg.setContent(multipart)
    setSubjectFromToCcBccReplyTo( msg, subject, from, to, cc, bcc, replyTo )
    msg.saveChanges()
    msg
  end _composeSimpleHtmlPlaintextAlternative

  /**
    * Does not set `msg.setSentDate(...)`.
    * Be sure to do so, and then `msg.saveChanges()` before sending!
    */
  def composeSimpleHtmlPlaintextAlternative[A:AddressesRep,B:AddressesRep,C:AddressesRep,D:AddressesRep,E:AddressesRep](
    html      : String,
    plaintext : String,
    subject   : String,
    from      : A,
    to        : B,
    cc        : C = Seq.empty[Address],
    bcc       : D = Seq.empty[Address],
    replyTo   : E = Seq.empty[Address],
    strict    : Boolean = true
  )( using context : Smtp.Context ) : MimeMessage =
    _composeSimpleHtmlPlaintextAlternative(
      html,
      plaintext,
      subject,
      summon[AddressesRep[A]].toAddresses(from, strict),
      summon[AddressesRep[B]].toAddresses(to, strict),
      summon[AddressesRep[C]].toAddresses(cc, strict),
      summon[AddressesRep[D]].toAddresses(bcc, strict),
      summon[AddressesRep[E]].toAddresses(replyTo, strict)
    )
  end composeSimpleHtmlPlaintextAlternative 

  def _sendSimpleHtmlPlaintextAlternative(
    html      : String,
    plaintext : String,
    subject   : String,
    from      : Seq[Address],
    to        : Seq[Address],
    cc        : Seq[Address],
    bcc       : Seq[Address],
    replyTo   : Seq[Address],
  )( using context : Smtp.Context ) : Unit =
    val msg = _composeSimpleHtmlPlaintextAlternative( html, plaintext, subject, from, to, cc, bcc, replyTo )
    msg.setSentDate(new Date())
    msg.saveChanges()
    context.sendMessage(msg)
  end _sendSimpleHtmlPlaintextAlternative

  def sendSimpleHtmlPlaintextAlternative[A:AddressesRep,B:AddressesRep,C:AddressesRep,D:AddressesRep,E:AddressesRep](
    html      : String,
    plaintext : String,
    subject   : String,
    from      : A,
    to        : B,
    cc        : C = Seq.empty[Address],
    bcc       : D = Seq.empty[Address],
    replyTo   : E = Seq.empty[Address],
    strict    : Boolean = true
  )( using context : Smtp.Context ) : Unit =
    _sendSimpleHtmlPlaintextAlternative(
      html,
      plaintext,
      subject,
      summon[AddressesRep[A]].toAddresses(from, strict),
      summon[AddressesRep[B]].toAddresses(to, strict),
      summon[AddressesRep[C]].toAddresses(cc, strict),
      summon[AddressesRep[D]].toAddresses(bcc, strict),
      summon[AddressesRep[E]].toAddresses(replyTo, strict)
    )
  end sendSimpleHtmlPlaintextAlternative 
