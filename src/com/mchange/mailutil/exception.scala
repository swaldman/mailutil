package com.mchange.mailutil

class MailutilException( message : String, cause : Throwable = null ) extends Exception(message, cause)

class SmtpAddressParseFailed( message : String, cause : Throwable = null ) extends MailutilException(message, cause)
class SmtpInitializationFailed( message : String, cause : Throwable = null ) extends MailutilException(message, cause)

