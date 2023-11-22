# mailutil

## Introduction

For now, this package just contains an API for super-simple sending by SMTP of plaintext and 
html-with-plaintext-alternative email.

## Quickstart

Putting aside [configuration](#configuration), sending a plaintext e-mail is just...

```scala
//> using dep "com.mchange::mailutil:0.0.1"

import com.mchange.mailutil.Smtp

val contents = "This is some really exciting text here."
Smtp.sendSimplePlaintext( contents, subject = "So exciting!", from = "swaldman@mchange.com", to = "you@reader.org" )
```

That's it!

(But you really do have to have [configured](#configuration) SMTP first.)

## Flexible addressing

* Wherever you can write an e-mail address like `you@reader.org`, you can also include a display name part, so
`Very Special You <you@reader.org>` would be fine, and many mail clients will display `Very Special You` as
the `to` address.

* Wherever you can write an e-mail address, you can write a comma-separated list of e-mail addresses, in
any acceptable format. So, for example,

  ```scala
  Smtp.sendSimplePlaintext( contents, 
    subject = "So exciting!", 
    from = "swaldman@mchange.com", 
    to = "Kitty <k@kitty.cat>, Doggy<d@d.og>, archive@animals.org" 
  )
  ```

  is fine.

* In addition to `from` and `to`, you can provide `cc`, `bcc`, and `replyTo`

## HTML mail

It's easy to send self-contained HTML mail.

"Self-contained" means no inclusion of, for example, images bundled into the mail.
You can link to whatever you want, in `a` or `img` tags or whatever, although mail clients
may be cautious about those references.

You will usually want to include CSS inline via a `<style> ... </style>` section under `<head>`.

For now, a plaintext alternative is required.

```scala
//> using dep "com.mchange::mailutil:0.0.1"

import com.mchange.mailutil.Smtp

val plaintext = "This is some really exciting text here."
val html =
  """|<html>
     |  <head>
     |    <title>So exciting!</title>
     |    <style>
     |      body {
     |        background-color: black
     |        color: white;
     |      }
     |      h1 {
     |        color: cyan;
     |      }
     |    </style>
     |  </head>
     |  <body>
     |    <h1>So exciting!</h1>
     |    <p>This is some really exciting text here.</p>
     |  </body>
     |</html>""".stripMargin
Smtp.sendSimpleHtmlPlaintextAlternative( html = html, plaintext = plaintext, subject = "So exciting!", from = "swaldman@mchange.com", to = "you@reader.org" )
```

## Raw access to the jakarta-mail API

Once [configured](#configuration), you can use this library just for easy access to the raw jakarta-mail API:

```scala
import com.mchange.mailutil.Smtp

def customSendMail()( using Smtp.Context ) =
  val ctx = summon[Smtp.Context]
  val msg = new MimeMessage( ctx.session ) // ctx.session is just a jakarta.mail.Session
  // do custom stuff
  msg.setSentDate(new Date())
  msg.saveChanges()
  ctx.sendMessage(msg) // use this send message to inherit authentication / configuration
```

## Configuration

### Via a properties file

The recommended configuration strategy is to define a Java-standard properties file,
and specify its absolute location using either

* the system property `mail.smtp.properties`; or
* the environment variable `SMTP_PROPERTIES`

This approach keeps more sensitive information out of the environment or the command
line, all of which can leak by, for example, use of the `ps` command.

Configuration properties include...

```plaintext
mail.smtp.host=???
mail.smtp.port=???
mail.smtp.auth=???
mail.smtp.user=???
mail.smtp.password=???         # NOT a standard javax.mail property AFAIK
mail.smtp.starttls.enable=???  # Usually omit, we'll figure it out from the port provided
mail.smtp.debug=???            # Log extra debugging information
```

### On the command line, or in the environment

If you prefer to live dangerously, any or all of these properties can also be provided
directly as system properties, or in the following environment variables:

```plaintext
SMTP_HOST=???
SMTP_PORT=???
SMTP_USER=???
SMTP_PASSWORD=???
SMTP_STARTTLS=???
SMTP_START_TLS=??? # Same as SMTP_STARTTLS, just an alternative form
SMTP_DEBUG=???
```

### In code

You can also configure your SMTP provder directly in code:

```scala
val smtpAuth = Smtp.Auth("smtpuser", "supersecretpassword") // better to fetch the password from somewhere than hardcode it!

given Smtp.Context(
  host = ???, // String
  port = ???, // Int
  auth = Some( smtpAuth ),
  startTls = ???, // boolean
  debug = ??? // boolean
)
Smtp.sendSimplePlaintext( contents, subject = "So exciting!", from = "swaldman@mchange.com", to = "you@reader.org" )
```

---

That's all for now!

