package org.infinispan.commands.tx;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.concurrent.CompletableFuture;

import org.infinispan.context.InvocationContext;
import org.infinispan.context.InvocationContextFactory;
import org.infinispan.context.impl.RemoteTxInvocationContext;
import org.infinispan.interceptors.AsyncInterceptorChain;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.remoting.transport.Address;
import org.infinispan.transaction.impl.RemoteTransaction;
import org.infinispan.transaction.impl.TransactionTable;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.ByteString;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * An abstract transaction boundary command that holds a reference to a {@link org.infinispan.transaction.xa.GlobalTransaction}
 *
 * @author Manik Surtani (<a href="mailto:manik@jboss.org">manik@jboss.org</a>)
 * @author Mircea.Markus@jboss.com
 * @since 4.0
 */
public abstract class AbstractTransactionBoundaryCommand implements TransactionBoundaryCommand {

   private static final Log log = LogFactory.getLog(AbstractTransactionBoundaryCommand.class);
   private static boolean trace = log.isTraceEnabled();

   protected GlobalTransaction globalTx;
   protected final ByteString cacheName;
   protected AsyncInterceptorChain invoker;
   protected InvocationContextFactory icf;
   protected TransactionTable txTable;
   private Address origin;
   private int topologyId = -1;

   public AbstractTransactionBoundaryCommand(ByteString cacheName) {
      this.cacheName = cacheName;
   }

   public void init(AsyncInterceptorChain chain, InvocationContextFactory icf, TransactionTable txTable) {
      this.invoker = chain;
      this.icf = icf;
      this.txTable = txTable;
   }

   @Override
   public int getTopologyId() {
      return topologyId;
   }

   @Override
   public void setTopologyId(int topologyId) {
      this.topologyId = topologyId;
   }

   @Override
   public ByteString getCacheName() {
      return cacheName;
   }

   @Override
   public GlobalTransaction getGlobalTransaction() {
      return globalTx;
   }

   @Override
   public void markTransactionAsRemote(boolean isRemote) {
      globalTx.setRemote(isRemote);
   }

   /**
    * This is what is returned to remote callers when an invalid RemoteTransaction is encountered.  Can happen if a
    * remote node propagates a transactional call to the current node, and the current node has no idea of the transaction
    * in question.  Can happen during rehashing, when ownerships are reassigned during a transactions.
    *
    * Returning a null usually means the transactional command succeeded.
    * @return return value to respond to a remote caller with if the transaction context is invalid.
    */
   protected Object invalidRemoteTxReturnValue() {
      return null;
   }

   @Override
   public Object perform(InvocationContext ctx) throws Throwable {
      return null;
   }

   @Override
   public CompletableFuture<Object> invokeAsync() throws Throwable {
      markGtxAsRemote();
      RemoteTransaction transaction = getRemoteTransaction();
      if (transaction == null) {
         if (trace) log.tracef("Did not find a RemoteTransaction for %s", globalTx);
         return CompletableFuture.completedFuture(invalidRemoteTxReturnValue());
      }
      visitRemoteTransaction(transaction);
      RemoteTxInvocationContext ctx = icf.createRemoteTxInvocationContext(transaction, getOrigin());

      if (trace) log.tracef("About to execute tx command %s", this);
      return invoker.invokeAsync(ctx, this);
   }

   protected void visitRemoteTransaction(RemoteTransaction tx) {
      // to be overridden
   }

   protected RemoteTransaction getRemoteTransaction() {
      return txTable.getRemoteTransaction(globalTx);
   }

   @Override
   public void writeTo(ObjectOutput output) throws IOException {
      output.writeObject(globalTx);
   }

   @Override
   public void readFrom(ObjectInput input) throws IOException, ClassNotFoundException {
      globalTx = (GlobalTransaction) input.readObject();
   }

   @Override
   public boolean shouldInvoke(InvocationContext ctx) {
      return true;
   }

   @Override
   public boolean ignoreCommandOnStatus(ComponentStatus status) {
      return false;
   }

   @Override
   public boolean readsExistingValues() {
      return false;
   }

   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      AbstractTransactionBoundaryCommand that = (AbstractTransactionBoundaryCommand) o;
      return this.globalTx.equals(that.globalTx);
   }

   public int hashCode() {
      return globalTx.hashCode();
   }

   @Override
   public String toString() {
      return "gtx=" + globalTx +
            ", cacheName='" + cacheName + '\'' +
            ", topologyId=" + topologyId +
            '}';
   }

   private void markGtxAsRemote() {
      globalTx.setRemote(true);
   }

   @Override
   public Address getOrigin() {
      return origin;
   }

   @Override
   public void setOrigin(Address origin) {
      this.origin = origin;
   }

   @Override
   public boolean isReturnValueExpected() {
      return true;
   }

   @Override
   public final boolean canBlock() {
      //all tx commands must wait for the correct topology
      return true;
   }
}
