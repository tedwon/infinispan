package org.infinispan.commands.functional;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.infinispan.commands.Visitor;
import org.infinispan.commons.api.functional.EntryView.WriteEntryView;
import org.infinispan.commons.marshall.MarshallUtil;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.context.InvocationContext;
import org.infinispan.functional.impl.EntryViews;
import org.infinispan.functional.impl.Params;
import org.infinispan.lifecycle.ComponentStatus;

public final class WriteOnlyManyCommand<K, V> extends AbstractWriteManyCommand<K, V> {

   public static final byte COMMAND_ID = 56;

   private Collection<? extends K> keys;
   private Consumer<WriteEntryView<V>> f;

   public WriteOnlyManyCommand(Collection<? extends K> keys, Consumer<WriteEntryView<V>> f, Params params) {
      this.keys = keys;
      this.f = f;
      this.params = params;
   }

   public WriteOnlyManyCommand(WriteOnlyManyCommand<K, V> command) {
      this.keys = command.getKeys();
      this.f = command.f;
      this.params = command.params;
   }

   public WriteOnlyManyCommand() {
   }

   public Collection<? extends K> getKeys() {
      return keys;
   }

   public void setKeys(Collection<? extends K> keys) {
      this.keys = keys;
   }

   @Override
   public byte getCommandId() {
      return COMMAND_ID;
   }

   @Override
   public void writeTo(ObjectOutput output) throws IOException {
      MarshallUtil.marshallCollection(keys, output);
      output.writeObject(f);
      output.writeBoolean(isForwarded);
      Params.writeObject(output, params);
   }

   @Override
   public void readFrom(ObjectInput input) throws IOException, ClassNotFoundException {
      keys = MarshallUtil.unmarshallCollectionUnbounded(input, ArrayList::new);
      f = (Consumer<WriteEntryView<V>>) input.readObject();
      isForwarded = input.readBoolean();
      params = Params.readObject(input);
   }

   @Override
   public Object acceptVisitor(InvocationContext ctx, Visitor visitor) throws Throwable {
      return visitor.visitWriteOnlyManyCommand(ctx, this);
   }

   @Override
   public Object perform(InvocationContext ctx) throws Throwable {
      // Can't return a lazy stream here because the current code in
      // EntryWrappingInterceptor expects any changes to be done eagerly,
      // otherwise they're not applied. So, apply the function eagerly and
      // return a lazy stream of the void returns.

      // TODO: Simplify with a collect() call
      List<Void> returns = new ArrayList<>(keys.size());
      keys.forEach(k -> {
         CacheEntry<K, V> cacheEntry = ctx.lookupEntry(k);

         // Could be that the key is not local, 'null' is how this is signalled
         if (cacheEntry != null) {
            f.accept(EntryViews.writeOnly(cacheEntry));
            returns.add(null);
         }
      });
      return returns.stream();
   }

   @Override
   public boolean isReturnValueExpected() {
      return false;  // TODO: Customise this generated block
   }

   @Override
   public boolean canBlock() {
      return false;  // TODO: Customise this generated block
   }

   @Override
   public Set<Object> getAffectedKeys() {
      return null;  // TODO: Customise this generated block
   }

   @Override
   public void updateStatusFromRemoteResponse(Object remoteResponse) {
      // TODO: Customise this generated block
   }

   @Override
   public boolean ignoreCommandOnStatus(ComponentStatus status) {
      return false;  // TODO: Customise this generated block
   }

   @Override
   public boolean readsExistingValues() {
      return false;
   }

   @Override
   public boolean alwaysReadsExistingValues() {
      return false;
   }

   @Override
   public boolean isWriteOnly() {
      return true;
   }

}
