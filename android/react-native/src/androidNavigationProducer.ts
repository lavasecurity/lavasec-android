import type {Spec} from '../canonical/specs/NativeLavaApp';

const INT_MAX=2147483647;
const EVENT_LIMIT=256;
export type AndroidProducerStatus='none'|'pending'|'accepted'|'refused';
export type AndroidProducerFacts={
  qaTools:boolean;snapshotRevision?:number;hydrationEpoch:number;hydrationRequired:boolean;
  presentationSerial:number;presentationStatus:AndroidProducerStatus;presentationAuthority:boolean;securityReadRevision?:number;
};
export const androidProducerCommandCounters=[
  'commands','presentationCommands','presentationReadyCommands','authorizationCommands','endTurnCommands',
  'filterOpenCommands','filterCloseCommands','filterEditCommands','filterSaveCommands','filterReviewCommands',
  'filterApplyCommands','filterCancelCommands','filterDomainCommands','queryCommands','hapticCommands','refreshCommands','otherCommands',
] as const;
type CommandCounter=typeof androidProducerCommandCounters[number];
export type AndroidProducerRow={
  events:number;renders:number;commits:number;snapshotRevision:number;hydrationEpoch:number;presentationSerial:number;securityReadRevision:number;
  snapshotPresent:boolean;hydrationRequired:boolean;presentationAuthority:boolean;securityReadRevisionPresent:boolean;presentationStatus:AndroidProducerStatus;
}&Record<CommandCounter,number>;
const commandTypes:ReadonlyArray<readonly[string,CommandCounter]>=[
  ['navigation.presentation','presentationCommands'],['navigation.presentationReady','presentationReadyCommands'],
  ['navigation.authorize','authorizationCommands'],['navigation.endTurn','endTurnCommands'],
  ['filter.open','filterOpenCommands'],['filter.close','filterCloseCommands'],['filter.edit','filterEditCommands'],
  ['filter.save','filterSaveCommands'],['filter.review','filterReviewCommands'],['filter.apply','filterApplyCommands'],
  ['filter.cancel','filterCancelCommands'],['filter.domain','filterDomainCommands'],
  ['activity.query','queryCommands'],['domains.query','queryCommands'],['catalog.query','queryCommands'],
  ['network.query','queryCommands'],['stats.query','queryCommands'],['share.query','queryCommands'],
  ['haptic','hapticCommands'],['refresh','refreshCommands'],
];
const prefixes=commandTypes.map(([type,counter])=>[`{"type":"${type}"`,counter] as const);
const integer=(value:unknown)=>typeof value==='number'&&Number.isFinite(value)?Math.min(INT_MAX,Math.max(0,Math.floor(value))):0;
const increment=(value:number)=>Math.min(INT_MAX,value+1);
const present=(value:unknown)=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=0;
const status=(value:unknown):AndroidProducerStatus=>value==='pending'||value==='accepted'||value==='refused'?value:'none';

type SavePlusCommandKind='filter-save'|'authorize';
type SavePlusCommandReceipt={request:number;kind:SavePlusCommandKind;commands:number;filterSaveCommands:number;authorizationCommands:number};
export type AndroidSavePlusCommandRow=SavePlusCommandReceipt&{schema:1;event:number;phase:'request'|'resolved'|'rejected'};

/** One root's QA observer. It owns no state subscription, scheduling or product actions. */
export class AndroidNavigationProducer {
  private renders=0;
  private commits=0;
  private events=0;
  private enabled=false;
  private commandTraceEvents=0;
  private commandTraceRequests=0;
  private previousReceipt:string|undefined;
  private counters=Object.fromEntries(androidProducerCommandCounters.map(key=>[key,0])) as Record<CommandCounter,number>;
  constructor(private readonly emit:(row:AndroidProducerRow)=>void=row=>console.info('LavaNavigationProducerTrace '+JSON.stringify(row)),
    private readonly emitCommand:(row:AndroidSavePlusCommandRow)=>void=row=>console.info('LavaSavePlusCommandTrace '+JSON.stringify(row))) {}
  render(){this.renders=increment(this.renders);}
  private command(request:string){
    if(!this.enabled)return;
    this.counters.commands=increment(this.counters.commands);
    // AppStore sends JSON.stringify(command). Inspect only an exact, fixed public
    // type prefix; never parse the payload or retain an arbitrary type/input.
    const counter=typeof request==='string'?prefixes.find(([prefix])=>request.startsWith(prefix)&&(request[prefix.length]===','||request[prefix.length]==='}'))?.[1]??'otherCommands':'otherCommands';
    this.counters[counter]=increment(this.counters[counter]);
  }
  private commandTrace(receipt:SavePlusCommandReceipt,phase:AndroidSavePlusCommandRow['phase']){
    if(!this.enabled||this.commandTraceEvents>=512)return;
    try{this.emitCommand({schema:1,event:++this.commandTraceEvents,phase,...receipt});}catch{}
  }
  private beginCommandTrace(request:string):SavePlusCommandReceipt|undefined {
    if(!this.enabled||this.commandTraceEvents>=512||typeof request!=='string')return;
    // The same exact public prefix check as the existing counters; no JSON/payload parsing.
    const counter=prefixes.find(([prefix])=>request.startsWith(prefix)&&(request[prefix.length]===','||request[prefix.length]==='}'))?.[1];
    const kind:SavePlusCommandKind|undefined=counter==='filterSaveCommands'?'filter-save':counter==='authorizationCommands'?'authorize':undefined;
    if(!kind)return;
    const receipt={request:++this.commandTraceRequests,kind,commands:this.counters.commands,
      filterSaveCommands:this.counters.filterSaveCommands,authorizationCommands:this.counters.authorizationCommands};
    this.commandTrace(receipt,'request');return receipt;
  }
  observe(native:Spec):Spec {
    return {
      getSnapshot:()=>native.getSnapshot(),
      onSnapshot:listener=>native.onSnapshot(listener),
      command:request=>{
        this.command(request);
        const receipt=this.beginCommandTrace(request);
        const promise=native.command(request);
        // Observe settlement without inspecting its result/error or replacing the original Promise.
        if(receipt)try{void promise.then(()=>this.commandTrace(receipt,'resolved'),()=>this.commandTrace(receipt,'rejected'));}catch{}
        return promise;
      },
    };
  }
  /** Facts are read from the committed root. No diagnostic operation triggers another render. */
  commit(facts:AndroidProducerFacts){
    this.commits=increment(this.commits);this.enabled=facts.qaTools===true;
    if(!this.enabled||this.events>=EVENT_LIMIT)return;
    const receipt=JSON.stringify([integer(facts.hydrationEpoch),facts.hydrationRequired===true,integer(facts.presentationSerial),status(facts.presentationStatus),facts.presentationAuthority===true,integer(facts.securityReadRevision)]);
    const changed=this.previousReceipt!==receipt;this.previousReceipt=receipt;
    // Record receipt changes and one in four ordinary root commits. A native
    // snapshot burst is visible through its revision and cumulative counters.
    if(this.events!==0&&!changed&&this.commits%4!==0)return;
    const row:AndroidProducerRow={
      events:++this.events,renders:this.renders,commits:this.commits,
      snapshotRevision:integer(facts.snapshotRevision),snapshotPresent:present(facts.snapshotRevision),
      hydrationEpoch:integer(facts.hydrationEpoch),hydrationRequired:facts.hydrationRequired===true,
      presentationSerial:integer(facts.presentationSerial),presentationStatus:status(facts.presentationStatus),
      presentationAuthority:facts.presentationAuthority===true,
      securityReadRevision:integer(facts.securityReadRevision),securityReadRevisionPresent:present(facts.securityReadRevision),
      ...this.counters,
    };
    // Logging is diagnostic-only. A failed sink consumes this bounded record;
    // it must not crash the product or schedule a retry. Native errors are never caught here.
    try{this.emit(row);}catch{}
  }
}
