/**
 * @format
 */

import { AppRegistry } from 'react-native';
import App from './CanonicalApp';
import PasscodeRoot from './canonical/review/PasscodeRoot.android';
import QARoot from './canonical/review/QARoot.android';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => App);
AppRegistry.registerComponent('LavaPasscode', () => PasscodeRoot);
AppRegistry.registerComponent('LavaQA', () => QARoot);
