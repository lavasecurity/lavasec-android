import {useEffect, useRef, useState, useSyncExternalStore} from 'react';
import {Alert, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View} from 'react-native';
import {localized} from '../app/presentation';
import {colors} from '../src/colors';

export type FilterIdentityTemplate = {id: string; name: string; emoji?: string};
export type FilterIdentityRequest =
  | {kind: 'create'; templates: readonly FilterIdentityTemplate[]}
  | {kind: 'rename'; initialName: string; initialEmoji: string};
export type FilterIdentityResult =
  | {kind: 'create'; duplicateFromID?: string}
  | {kind: 'rename'; name: string; emoji: string}
  | null;

type Pending = {id: number; request: FilterIdentityRequest; resolve: (result: FilterIdentityResult) => void};
let pending: Pending | null = null;
let sequence = 0;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach(listener => listener());

function settle(id: number, result: FilterIdentityResult) {
  const active = pending;
  // A dismissed dialog or its discard alert cannot complete a replacement request.
  if (!active || active.id !== id) return;
  pending = null;
  emit();
  active.resolve(result);
}

/** The frozen Create sheet chooses a template; Rename collects both identity fields. */
export function presentFilterIdentityForm(request: FilterIdentityRequest): Promise<FilterIdentityResult> {
  return new Promise(resolve => {
    const superseded = pending;
    const captured = request.kind === 'create'
      ? {...request, templates: request.templates.map(template => ({...template}))}
      : {...request};
    pending = {id: ++sequence, request: captured, resolve};
    if (superseded) superseded.resolve(null);
    emit();
  });
}

export function dismissFilterIdentityForm() {
  if (pending) settle(pending.id, null);
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};
const getSnapshot = () => pending;

export function FilterIdentityFormHost() {
  const active = useSyncExternalStore(subscribe, getSnapshot);
  const ownedID = useRef<number | null>(null);
  ownedID.current = active?.id ?? null;
  useEffect(() => () => {
    if (ownedID.current !== null) settle(ownedID.current, null);
  }, []);
  if (!active) return null;
  return <FilterIdentityDialog key={active.id} request={active.request} resolve={result => settle(active.id, result)} />;
}

function FilterIdentityDialog({request, resolve}: {request: FilterIdentityRequest; resolve: (result: FilterIdentityResult) => void}) {
  const [name, setName] = useState(request.kind === 'rename' ? request.initialName : '');
  const [emoji, setEmoji] = useState(request.kind === 'rename' ? request.initialEmoji : '');
  const [duplicateFromID, setDuplicateFromID] = useState<string | undefined>();
  const creating = request.kind === 'create';
  // Native FilterIdentityPolicy owns Unicode emoji, name grammar, uniqueness and legacy names.
  // Never reimplement that admission policy with a JS regex.
  const canConfirm = creating || (name.trim().length > 0 && emoji.length > 0);
  const confirm = () => {
    if (!canConfirm) return;
    resolve(creating ? {kind: 'create', duplicateFromID} : {kind: 'rename', name, emoji});
  };
  const cancel = () => {
    if (request.kind === 'rename' && (name !== request.initialName || emoji !== request.initialEmoji)) {
      Alert.alert(localized('Discard changes?'), undefined, [
        {text: localized('Keep Editing'), style: 'cancel'},
        {text: localized('Discard Changes'), style: 'destructive', onPress: () => resolve(null)},
      ]);
    } else resolve(null);
  };
  const templateRow = (title: string, id?: string, icon?: string) => (
    <Pressable key={id === undefined ? 'new' : `saved.${id}`} testID={id === undefined ? 'filter.identity.template.new' : `filter.identity.template.saved.${id}`}
      accessibilityRole="radio" accessibilityLabel={title}
      accessibilityState={{selected: duplicateFromID === id}}
      onPress={() => setDuplicateFromID(id)} style={styles.template}>
      <Text style={styles.templateLabel}>{icon ? `${icon} ${title}` : title}</Text>
      <Text accessibilityElementsHidden importantForAccessibility="no" style={styles.templateLabel}>{duplicateFromID === id ? '●' : '○'}</Text>
    </Pressable>
  );
  return (
    <Modal transparent visible animationType="fade" onRequestClose={cancel}>
      <View style={styles.backdrop}>
        <View style={styles.card}>
          <Text style={styles.title}>{creating ? localized('New filter') : localized('Rename filter')}</Text>
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.fields}>
            {request.kind === 'create' ? <>
              <Text style={styles.fieldLabel}>{localized('Start from')}</Text>
              {templateRow(localized('Create new'))}
              {request.templates.map(template => templateRow(template.name, template.id, template.emoji))}
            </> : <>
              <Text style={styles.fieldLabel}>{localized('Emoji')}</Text>
              <TextInput testID="filter.identity.emoji" accessibilityLabel={localized('Emoji')}
                autoCorrect={false} autoCapitalize="none" value={emoji} onChangeText={setEmoji} style={styles.input} />
              <Text style={styles.fieldLabel}>{localized('Name')}</Text>
              <TextInput testID="filter.identity.name" accessibilityLabel={localized('Filter name')}
                autoFocus value={name} onChangeText={setName} placeholder={localized('Filter name')}
                placeholderTextColor={colors.secondaryText} style={styles.input} onSubmitEditing={confirm} />
            </>}
          </ScrollView>
          <View style={styles.actions}>
            <Pressable testID="filter.identity.cancel" accessibilityRole="button" onPress={cancel} style={styles.button}>
              <Text style={styles.buttonLabel}>{localized('Cancel')}</Text>
            </Pressable>
            <Pressable testID="filter.identity.confirm" accessibilityRole="button" disabled={!canConfirm}
              onPress={confirm} style={[styles.button, !canConfirm && styles.disabled]}>
              <Text style={styles.buttonLabel}>{creating ? localized('Create') : localized('Save')}</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.4)'},
  card: {width: '85%', maxHeight: '85%', borderRadius: 14, padding: 20, gap: 16, backgroundColor: colors.cardBackground},
  title: {fontSize: 18, fontWeight: '600', color: colors.primaryText},
  fields: {gap: 12},
  fieldLabel: {fontSize: 16, color: colors.primaryText},
  input: {borderWidth: StyleSheet.hairlineWidth, borderColor: colors.separator, borderRadius: 8, paddingHorizontal: 12, paddingVertical: 10, fontSize: 16, color: colors.primaryText},
  template: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12, paddingVertical: 12},
  templateLabel: {flexShrink: 1, fontSize: 16, color: colors.primaryText},
  actions: {flexDirection: 'row', justifyContent: 'flex-end', gap: 16},
  button: {paddingHorizontal: 8, paddingVertical: 6},
  buttonLabel: {color: colors.primaryText},
  disabled: {opacity: 0.4},
});
