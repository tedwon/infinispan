package org.infinispan.interceptors.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;

import org.infinispan.commands.VisitableCommand;
import org.infinispan.context.InvocationContext;
import org.infinispan.interceptors.BasicInvocationStage;
import org.infinispan.interceptors.InvocationComposeHandler;
import org.infinispan.interceptors.InvocationComposeSuccessHandler;
import org.infinispan.interceptors.InvocationExceptionHandler;
import org.infinispan.interceptors.InvocationFinallyHandler;
import org.infinispan.interceptors.InvocationReturnValueHandler;
import org.infinispan.interceptors.InvocationStage;
import org.infinispan.interceptors.InvocationSuccessHandler;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * @author Dan Berindei
 * @since 9.0
 */
public class ComposedAsyncInvocationStage extends AbstractInvocationStage
      implements InvocationStage, BiFunction<BasicInvocationStage, Throwable, BasicInvocationStage> {
   private static final Log log = LogFactory.getLog(ComposedAsyncInvocationStage.class);
   private static final boolean trace = log.isTraceEnabled();

   private final InvocationComposeHandler handler;
   private CompletableFuture<BasicInvocationStage> stageFuture;

   public ComposedAsyncInvocationStage(InvocationContext ctx, VisitableCommand command,
                                       CompletableFuture<BasicInvocationStage> stageFuture) {
      super(ctx, command);
      this.handler = null;
      this.stageFuture = stageFuture;
   }

   private ComposedAsyncInvocationStage(InvocationContext ctx, VisitableCommand command,
         InvocationComposeHandler handler) {
      super(ctx, command);
      this.handler = handler;
   }

   @Override
   public Object get() throws Throwable {
      try {
         BasicInvocationStage stage = stageFuture.join();
         return stage.get();
      } catch (CompletionException e) {
         throw e.getCause();
      }
   }

   @Override
   public InvocationStage compose(InvocationComposeHandler composeHandler) {
      // The handler field isn't thread-safe if we publish a reference to `this` before the constructor ends
      ComposedAsyncInvocationStage composedStage = new ComposedAsyncInvocationStage(ctx, command, composeHandler);
      composedStage.stageFuture = stageFuture.handle(composedStage);
      return composedStage;
   }

   @Override
   public InvocationStage thenCompose(InvocationComposeSuccessHandler thenComposeHandler) {
      return compose(thenComposeHandler);
   }

   @Override
   public InvocationStage thenAccept(InvocationSuccessHandler successHandler) {
      return compose(successHandler);
   }

   @Override
   public InvocationStage thenApply(InvocationReturnValueHandler returnValueHandler) {
      return compose(returnValueHandler);
   }

   @Override
   public InvocationStage exceptionally(InvocationExceptionHandler exceptionHandler) {
      return compose(exceptionHandler);
   }

   @Override
   public InvocationStage handle(InvocationFinallyHandler finallyHandler) {
      return compose(finallyHandler);
   }

   @Override
   public CompletableFuture<Object> toCompletableFuture() {
      CompletableFuture<Object> cf = new CompletableFuture<>();
      stageFuture.whenComplete((stage, t) -> {
         if (t != null) {
            cf.completeExceptionally(t);
            return;
         }
         try {
            ((InvocationStage) stage).handle((rCtx, rCommand, rv, t1) -> {
               if (t1 == null) {
                  cf.complete(rv);
               } else {
                  cf.completeExceptionally(t1);
               }
            });
         } catch (Throwable t1) {
            cf.complete(t1);
         }
      });
      return cf;
   }

   @Override
   public InvocationStage toInvocationStage(InvocationContext newCtx, VisitableCommand newCommand) {
      if (newCtx != ctx || newCommand != command) {
         return new ComposedAsyncInvocationStage(newCtx, newCommand, stageFuture);
      }
      return this;
   }

   @Override
   public BasicInvocationStage apply(BasicInvocationStage stage, Throwable t) {
      Object rv;
      if (t != null) {
         stage = new ExceptionStage(ctx, command, CompletableFutures.extractException(t));
         rv = null;
      } else {
         try {
            rv = stage.get();
         } catch (Throwable t1) {
            t = t1;
            rv = null;
         }
      }

      if (handler == null) {
         return new ExceptionStage(ctx, command, new NullPointerException("Handler must be set for apply"));
      }
      try {
         if (trace) log.tracef("Executing invocation handler %s with command %s", Stages.className(handler), command);
         stage = handler.apply(stage, ctx, command, rv, t);
         // This stage won't execute any handlers, so we don't need updateCommand
      } catch (Throwable t1) {
         if (trace) log.tracef(t1, "Exception in invocation handler %s", handler);
         stage = new ExceptionStage(ctx, command, t1);
      }
      return stage;
   }
}
