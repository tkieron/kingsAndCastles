// The game engine compiled from Java by TeaVM (browser/ module). Every value goes in and out as JSON text.
declare module 'kc-engine' {
  export function rules(): string;
  export function newGame(setupJson: string): void;
  export function submit(kingdom: string, ordersJson: string): string;
  export function markPresent(kingdom: string): void;
  export function endTurn(): void;
  export function waitingFor(): string;
  export function turn(): number;
  export function finished(): boolean;
  export function humanOrders(): string;
  export function present(): string;
  export function view(me: string | null): string;
}
