import {localized,localizedFormat} from '../app/presentation';
import type {AppSnapshot} from '../app/contract';

/** Frozen catalog artwork and copy, with native-confirmed entitlement/achievement admission. */
type GuardLook = {
  id: string;
  displayName: string;
  description: string;
  tip: string;
};

const GUARD_LOOKS: GuardLook[] = [
  {
    id: 'original',
    displayName: 'Original',
    description: 'A Lava a day keeps bad domains away.',
    tip: 'Lava quietly blocks domains known for scams and malware, so most threats never load.',
  },
  {
    id: 'emberObsidian',
    displayName: 'Fire Opal',
    description: 'Always check the link first.',
    tip: 'A link can show one name but open another. Check where it really goes before you tap.',
  },
  {
    id: 'purpleObsidian',
    displayName: 'Amethyst',
    description: 'Block it once. Browse in peace.',
    tip: 'Switch on a blocklist once and Lava keeps catching those domains for you.',
  },
  {
    id: 'obsidian',
    displayName: 'Obsidian',
    description: 'Sign in where you meant to sign in.',
    tip: 'Some fake sites copy a real login page to steal your password. Open the app or type the address yourself.',
  },
  {
    id: 'strawberryObsidian',
    displayName: 'Cherry Quartz',
    description: 'Giveaways should not ask for secrets.',
    tip: "A real prize never needs your password or a one-time code. If it asks, it's a scam.",
  },
  {
    id: 'emerald',
    displayName: 'Emerald',
    description: 'Make me your web-surfing buddy!',
    tip: 'Keep Lava on while you browse and it watches for risky domains in the background.',
  },
  {
    id: 'kiwiCreme',
    displayName: 'Kiwi Crème',
    description: "Hey I'm no rock but I take security paw-sonally. U know what I mean?",
    tip: 'Small habits help: pause before you tap, and let Lava handle the domains you should skip.',
  },
  {
    id: 'aquamarine',
    displayName: 'Aquamarine',
    description: 'Something phishy? Swim away.',
    tip: 'If a message rushes you to click, pause. Open the official app or website yourself.',
  },
];

/** The canonical look whose persisted preference the original artwork stands in for. */
export const ORIGINAL_GUARD_ID = 'original';

export type GuardAvailability = {
  currentLook?:string;
  hasPlus?:boolean;
  unlockedIDs?:string[];
  qualifiedUsageDays?:number;
  progressEnabled?:boolean;
};
const goals:Record<string,number>={emberObsidian:3,purpleObsidian:7,obsidian:14,strawberryObsidian:30,emerald:60,kiwiCreme:90,aquamarine:120};

/** Earned looks and the retained current look stay available when an entitlement lapses. */
export function projectGuards(facts:GuardAvailability={}): AppSnapshot['guards'] {
  const available=(id:string)=>id===ORIGINAL_GUARD_ID||facts.hasPlus===true||id===facts.currentLook||facts.unlockedIDs?.includes(id)===true;
  const next=GUARD_LOOKS.find(look=>!available(look.id))?.id;
  return GUARD_LOOKS.map(look => {
    const selectable=available(look.id),required=goals[look.id];
    return {
      id:look.id,
      title:selectable?localized(look.displayName):localizedFormat('Use Lava %d days',required!),
      subtitle:selectable?'':facts.progressEnabled!==true?localized('Progress is off in Privacy & Data'):look.id===next?localizedFormat('Currently at: %d days',Math.min(facts.qualifiedUsageDays??0,required!)):'',
      selectable,
      description:localized(look.description),
      tip:localized(look.tip),
    };
  });
}
