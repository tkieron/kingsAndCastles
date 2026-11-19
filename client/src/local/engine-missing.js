// Stand-in used when the Java engine was not compiled to JavaScript (mvn -pl browser -am package).
// The normal online client never loads it; the offline version needs the real engine.
const missing = () => {
  throw new Error('The offline engine is missing: run "mvn -pl browser -am package" first');
};
export const rules = missing;
export const newGame = missing;
export const submit = missing;
export const markPresent = missing;
export const endTurn = missing;
export const waitingFor = missing;
export const turn = missing;
export const finished = missing;
export const humanOrders = missing;
export const present = missing;
export const view = missing;
