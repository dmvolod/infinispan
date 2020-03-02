package org.infinispan.server.memcached.logging;

import static org.jboss.logging.Logger.Level.ERROR;
import static org.jboss.logging.Logger.Level.WARN;

import org.infinispan.commons.CacheConfigurationException;
import org.jboss.logging.BasicLogger;
import org.jboss.logging.annotations.Cause;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

import java.util.Set;

/**
 * Log abstraction for the Memcached server module. For this module, message ids
 * ranging from 11001 to 12000 inclusively have been reserved.
 *
 * @author Galder Zamarreño
 * @since 5.0
 * @deprecated since 10.1. Will be removed unless a binary protocol encoder/decoder is implemented.
 */
@Deprecated
@MessageLogger(projectCode = "ISPN")
public interface Log extends BasicLogger {
   @LogMessage(level = ERROR)
   @Message(value = "Exception reported", id = 5003)
   void exceptionReported(@Cause Throwable t);

   @Message(value = "Cannot enable authentication without specifying a ServerAuthenticationProvider", id = 6005)
   CacheConfigurationException serverAuthenticationProvider();

   @Message(value = "The specified allowedMechs [%s] contains mechs which are unsupported by the underlying factories [%s]", id = 6006)
   CacheConfigurationException invalidAllowedMechs(Set<String> allowedMechs, Set<String> allMechs);

   @Message(value = "The requested operation is invalid", id = 6007)
   UnsupportedOperationException invalidOperation();

   @Message(value = "A serverName must be specified when enabling authentication", id = 6008)
   CacheConfigurationException missingServerName();

   @Message(value = "Factory '%s' not found in server", id = 6016)
   IllegalStateException missingKeyValueFilterConverterFactory(String name);

   @Message(value = "Unauthorized '%s' operation", id = 6017)
   SecurityException unauthorizedOperation(String op);

   @Message(value = "EXTERNAL SASL mechanism not allowed without SSL client certificate", id = 6018)
   SecurityException externalMechNotAllowedWithoutSSLClientCert();

   @Message(value = "Cache '%s' has expiration enabled which violates the Memcached protocol", id = 11001)
   CacheConfigurationException invalidExpiration(String cacheName);

   @LogMessage(level = WARN)
   @Message(value = "Removed unclosed iterator '%s'", id = 28026)
   void removedUnclosedIterator(String iteratorId);
}
