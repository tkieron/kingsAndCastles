// What the server sends. Mirrors server/src/main/java/pl/kingscastles/server/web/Views.java.

export type Controller = 'HUMAN' | 'AI_BEGINNER' | 'AI_ADVANCED' | 'AI_PRO';
export type TaxRate = 'LOW' | 'MEDIUM' | 'HIGH';
export type Mood = 'HAPPY' | 'NORMAL' | 'SAD';
export type TreatyType = 'PACT' | 'TRADE' | 'ALLIANCE';
export type BuildingType =
  | 'FARM' | 'MARKET' | 'GOLD_MINE' | 'INN' | 'TREASURY' | 'BARRACKS' | 'WALLS' | 'BRIDGE' | 'GREAT_PALACE';
export type Place = 'VILLAGE' | 'CASTLE' | 'SETTLEMENT' | 'HILLS_TILE' | 'RIVER_TILE';
export type UnitType = 'SPEARMAN' | 'ARCHER' | 'KNIGHT' | 'RAM' | 'SETTLER';
export type Status = 'LOBBY' | 'PLAYING' | 'FINISHED';

export interface Pos { x: number; y: number }

export interface RoomSettings {
  turnSeconds: number;
  maxTurns: number;
  seats: Controller[];
  emptySeatLevel: Controller;
}

export interface Seat {
  seat: number;
  kingdom: string;
  name: string;
  color: string;
  controller: Controller;
  player?: string;
}

export interface Lobby { code: string; status: Status; settings: RoomSettings; seats: Seat[] }

export interface KingdomView {
  id: string;
  name: string;
  color: string;
  playerName?: string;
  controller: Controller;
  autopilot: boolean;
  overlord?: string;
  mood: Mood;
  prestige: number;
  palaceDone: boolean;
  palacePaid: number;
  promiseBreaker: boolean;
  gold?: number;
  food?: number;
  loanLeft?: number;
  tax?: TaxRate;
}

export interface SettlementView {
  id: number;
  name: string;
  pos: Pos;
  castle: boolean;
  owner: string;
  families: number;
  capacity: number;
  buildings: BuildingType[];
}

export interface UnitView { id: number; owner: string; type: UnitType; pos: Pos }
export interface TreatyView { type: TreatyType; a: string; b: string; signedTurn: number; endsAfterTurn: number }
export interface ProposalView { from: string; to: string; type: TreatyType }

export interface ChronicleEvent { phase: number; key: string; visibleTo: string[]; params: Record<string, unknown> }
export interface LedgerLine { key: string; gold: number; params: Record<string, unknown> }
export interface ReportView {
  turn: number;
  events: ChronicleEvent[];
  ledger: LedgerLine[];
  prestige: Record<string, number>;
}

export interface MapView {
  width: number;
  height: number;
  /** M meadow, F forest, H hills, A mountain, R river, L lake */
  rows: string[];
  improvements: { pos: Pos; building: BuildingType }[];
  ruins: Pos[];
}

export interface GameState {
  code: string;
  status: Status;
  turn: number;
  maxTurns: number;
  turnEndsAt?: number;
  turnSeconds: number;
  foodPrice: number;
  me?: string;
  map: MapView;
  kingdoms: KingdomView[];
  settlements: SettlementView[];
  units: UnitView[];
  treaties: TreatyView[];
  proposals: ProposalView[];
  myOrders: Order[];
  ready: string[];
  finished: boolean;
  winners: string[];
  winReason?: string;
  report?: ReportView;
}

export interface Problem { orderIndex: number; key: string }

/** One order. "order" names the kind (the server's JSON tag); treaty orders have their own "type". */
export type Order =
  | { order: 'setTax'; rate: TaxRate }
  | { order: 'build'; settlementId: number; building: BuildingType }
  | { order: 'buildOnTile'; pos: Pos; building: BuildingType }
  | { order: 'recruit'; settlementId: number; unit: UnitType }
  | { order: 'move'; unitId: number; path: Pos[] }
  | { order: 'attack'; unitId: number; target: Pos }
  | { order: 'foundVillage'; unitId: number; name: string }
  | { order: 'gift'; to: string; gold: number; food: number }
  | { order: 'market'; buyFood: number; sellFood: number }
  | { order: 'takeLoan' }
  | { order: 'buyFreedom' }
  | { order: 'proposeTreaty'; to: string; type: TreatyType }
  | { order: 'acceptTreaty'; from: string; type: TreatyType }
  | { order: 'breakTreaty'; with: string; type: TreatyType };

export interface Rules {
  buildings: { type: BuildingType; cost: number; place: Place }[];
  units: { type: UnitType; cost: number; upkeep: number; attack: number; defense: number; move: number; needsBarracks: boolean }[];
  kingdoms: { id: string; name: string; color: string; castle: string; village: string }[];
  balance: Record<string, number>;
}
