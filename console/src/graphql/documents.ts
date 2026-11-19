import {
  readsSeveralShapes, shapeNamed,
  type MemberDescription, type ModelDescription, type ShapeDescription, type VelleRecord,
} from '../model/description';

// The GraphQL documents the console sends, built from the model description. The names follow the
// schema the API builds from the same model (apis/graphql, VelleGraphQL.kt): a shape's fetch by key
// is its name with a lower-case first letter, its query that name with an s, its count that name
// with Count, and an act's mutation the act's name with a lower-case first letter.

function lowerFirst(name: string): string {
  return name.charAt(0).toLowerCase() + name.slice(1);
}

/** The schema's field for a member: a member named shape or key gives way to the record's own. */
export function fieldNameOf(member: MemberDescription): string {
  return member.name === 'shape' || member.name === 'key' ? member.name + '_' : member.name;
}

/** A member's value in a record the API answered. */
export function memberValue(record: VelleRecord, member: MemberDescription): unknown {
  return record[fieldNameOf(member)];
}

/** What is asked of a referenced record: its shape and key, shape by shape when the type reads several. */
function referenceSelection(model: ModelDescription, typeName: string): string {
  const referencedShape = shapeNamed(model, typeName);
  if (!readsSeveralShapes(referencedShape)) return '{ shape key }';
  const perShape = referencedShape!.bases!.map(baseName => `... on ${baseName} { shape key }`);
  return `{ ${perShape.join(' ')} }`;
}

function memberSelection(model: ModelDescription, member: MemberDescription): string {
  const isReference = shapeNamed(model, member.type) !== undefined;
  return isReference ? `${fieldNameOf(member)} ${referenceSelection(model, member.type)}` : fieldNameOf(member);
}

function membersSelection(model: ModelDescription, members: MemberDescription[], flags: string[]): string {
  return ['shape', 'key', ...members.map(member => memberSelection(model, member)), ...flags].join(' ');
}

/** The members of a refinement over several shapes that one of those shapes has, asked on that shape. */
function selectionOnBase(model: ModelDescription, refinement: ShapeDescription, baseName: string): string {
  const base = shapeNamed(model, baseName);
  const baseMemberNames = base ? base.members.map(member => member.name) : [];
  const sharedMembers = refinement.members.filter(member => baseMemberNames.includes(member.name));
  return `... on ${baseName} { ${membersSelection(model, sharedMembers, [])} }`;
}

/** Everything the console shows of one record of a shape: every member and every flag. */
export function recordSelection(model: ModelDescription, shape: ShapeDescription): string {
  if (!readsSeveralShapes(shape)) return membersSelection(model, shape.members, shape.flags ?? []);
  return shape.bases!.map(baseName => selectionOnBase(model, shape, baseName)).join(' ');
}

function shapeOrFail(model: ModelDescription, shapeName: string): ShapeDescription {
  const shape = shapeNamed(model, shapeName);
  if (!shape) throw new Error(`The model has no shape or refinement named ${shapeName}.`);
  return shape;
}

/** One page of a shape's records. Variables: where, orderBy, first, after. Answers `page`. */
export function pageDocument(model: ModelDescription, shapeName: string): string {
  const shape = shapeOrFail(model, shapeName);
  return `query Page($where: String, $orderBy: [String!]!, $first: Int, $after: String) {
  page: ${lowerFirst(shapeName)}s(where: $where, orderBy: $orderBy, first: $first, after: $after) {
    items { ${recordSelection(model, shape)} }
    next
  }
}`;
}

/** How many records of a shape satisfy the predicate. Variables: where. Answers `count`. */
export function countDocument(shapeName: string): string {
  return `query Count($where: String) { count: ${lowerFirst(shapeName)}Count(where: $where) }`;
}

/** One record by its key. Variables: key. Answers `record`, null when there is none. */
export function recordDocument(model: ModelDescription, shapeName: string): string {
  const shape = shapeOrFail(model, shapeName);
  return `query Record($key: ID!) { record: ${lowerFirst(shapeName)}(key: $key) { ${recordSelection(model, shape)} } }`;
}

/** The commit of an act. Variables: input. Answers `outcome`. */
export function actDocument(actName: string): string {
  return `mutation Commit($input: ${actName}Input!) {
  outcome: ${lowerFirst(actName)}(input: $input) { accepted ref { shape key } reason rejection { message } }
}`;
}

/** Every schedule with the moment it last ran. Answers `ticks`. */
export const latestTicksDocument = 'query LatestTicks { ticks { schedule at } }';

/** A tick of one schedule now. Variables: schedule. Answers `tick`. */
export const tickDocument = 'mutation Tick($schedule: String!) { tick(schedule: $schedule) { at rejections { message } } }';
