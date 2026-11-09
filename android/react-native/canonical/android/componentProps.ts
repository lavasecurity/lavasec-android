import type {CodegenTypes, ColorValue, ViewProps} from 'react-native';
export interface LavaTextFieldProps extends ViewProps {
  textContentType?: string;
  inputLabel: string;
  autoFocus?: boolean;
  autoFocusReady?: CodegenTypes.WithDefault<boolean, true>;
  placeholder: string;
  kind?: string;
  value?: string;
  ownerID?: string;
  resetRevision: CodegenTypes.Int32;
  fontPointSize?: CodegenTypes.WithDefault<CodegenTypes.Float, 0>;
  lineHeight?: CodegenTypes.WithDefault<CodegenTypes.Float, 0>;
  editable?: CodegenTypes.WithDefault<boolean, true>;
  keyboardType?: string;
  autoCapitalize?: string;
  autoCorrect?: CodegenTypes.WithDefault<boolean, false>;
  spellCheck?: CodegenTypes.WithDefault<boolean, false>;
  smartInsertDelete?: CodegenTypes.WithDefault<boolean, false>;
  clearButtonMode?: string;
  characterLimit?: CodegenTypes.WithDefault<CodegenTypes.Int32, 0>;
  maxLength?: CodegenTypes.WithDefault<CodegenTypes.Int32, -1>;
  monospace?: CodegenTypes.WithDefault<boolean, false>;
  selectionColor?: ColorValue;
  textColor?: ColorValue;
  placeholderTextColor?: ColorValue;
  onSizeChange?: CodegenTypes.DirectEventHandler<Readonly<{height: CodegenTypes.Float}>>;
  onFocusChange?: CodegenTypes.DirectEventHandler<Readonly<{focused: boolean}>>;
  onChange?: CodegenTypes.DirectEventHandler<Readonly<{text: string}>>;
  onSubmit?: CodegenTypes.DirectEventHandler<Readonly<{text: string}>>;
}
export interface LavaSwitchProps extends ViewProps {
  label: string;
  value: boolean;
  optimistic: boolean;
  disabled: boolean;
  pending: boolean;
  resetRevision: CodegenTypes.Int32;
  tintColor?: ColorValue;
  onValueChange?: CodegenTypes.DirectEventHandler<Readonly<{value: boolean}>>;
  onSizeChange?: CodegenTypes.DirectEventHandler<Readonly<{width: CodegenTypes.Double; height: CodegenTypes.Double}>>;
}
export interface LavaSliderProps extends ViewProps {
  label: string;
  value: CodegenTypes.Double;
  maximum: CodegenTypes.Double;
  disabled: boolean;
  tintColor?: ColorValue;
  onTrackingChange?: CodegenTypes.DirectEventHandler<Readonly<{tracking: boolean}>>;
  onValueChange?: CodegenTypes.DirectEventHandler<Readonly<{value: CodegenTypes.Double}>>;
}
export interface LavaNativePageProps extends ViewProps {
  page: string;
  focused: boolean;
  onBack?: CodegenTypes.DirectEventHandler<Readonly<{}>>;
  onNavigate?: CodegenTypes.DirectEventHandler<Readonly<{destination: string}>>;
}
export interface LavaChoiceProps extends ViewProps {
  label: string;
  controlID: string;
  options: ReadonlyArray<Readonly<{value: string; label: string}>>;
  value: string;
  reselectValue?: string;
  disabled: boolean;
  selectionRevision?: CodegenTypes.WithDefault<CodegenTypes.Int32, 0>;
  stepper?: CodegenTypes.WithDefault<boolean, false>;
  pageControl?: CodegenTypes.WithDefault<boolean, false>;
  tintColor?: ColorValue;
  onValueChange?: CodegenTypes.DirectEventHandler<Readonly<{value: string}>>;
  onSizeChange?: CodegenTypes.DirectEventHandler<Readonly<{height: CodegenTypes.Double}>>;
}
export interface LavaDecorationProps extends ViewProps {
  staticExport?: CodegenTypes.WithDefault<boolean, false>;
  symbol?: string;
  mood?: string;
  look?: string;
  tone?: string;
  // Shared controls pin this to app appearance; other decoration inherits traits.
  colorScheme?: string;
  fontPointSize?: CodegenTypes.WithDefault<CodegenTypes.Float, 0>;
  fontWeight?: string;
  guardianGestures?: CodegenTypes.WithDefault<boolean, false>;
  revealEnabled?: CodegenTypes.WithDefault<boolean, false>;
  revealVisible?: CodegenTypes.WithDefault<boolean, true>;
  revealX?: CodegenTypes.WithDefault<CodegenTypes.Float, 13>;
  revealY?: CodegenTypes.WithDefault<CodegenTypes.Float, 13>;
  revealRadius?: CodegenTypes.WithDefault<CodegenTypes.Float, 22>;
  onGuardianGesture?: CodegenTypes.DirectEventHandler<Readonly<{gesture: string}>>;
}
export interface LavaContextMenuProps extends ViewProps {
  contextID:string;
  blockedTintColor?:ColorValue;
  actions: ReadonlyArray<Readonly<{id:string;title:string;symbol:string}>>;
  onAction?: CodegenTypes.DirectEventHandler<Readonly<{id:string}>>;
}
