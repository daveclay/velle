// The model description the API answers at GET <api>: every act, every readable shape and
// refinement, and the schedules (apis/json, Describe.kt). Everything the console shows is
// built from this and from the records the API answers.

export interface ModelDescription {
  authorization: boolean;
  schedules: string[];
  acts: ActDescription[];
  shapes: ShapeDescription[];
}

export interface ShapeDescription {
  name: string;
  kind: 'shape' | 'refinement';
  /** The shapes a refinement is over; more than one when it reads records of several shapes. */
  bases?: string[];
  /** True when the shape is committed as an act. */
  act?: boolean;
  members: MemberDescription[];
  /** The `is<Refinement>` members of a shape's records, one per refinement of it. */
  flags?: string[];
}

export interface MemberDescription {
  name: string;
  /** The spec's word for a value's type, or the shape a reference points at. */
  type: string;
  kind: 'stored' | 'derived' | 'inverse' | 'captured' | 'timestamp' | 'actor';
  optional: boolean;
  /** Whether a query may order by the member. The API decides that, never the console. */
  orderable: boolean;
}

export interface ActDescription {
  name: string;
  transient: boolean;
  actor: { field: string; shape: string } | null;
  fields: FieldDescription[];
  parts?: PartDescription[];
}

export interface FieldDescription {
  name: string;
  type: string;
  kind: 'value' | 'values' | 'reference' | 'references';
  optional: boolean;
  /** The reference may point at a record created in the same act. */
  mayBeNew?: boolean;
  /** The field may carry a new record of one of these shapes instead of a reference. */
  newValue?: NestedDescription[];
}

export interface NestedDescription {
  shape: string;
  fields: FieldDescription[];
  parts?: PartDescription[];
}

export interface PartDescription {
  /** The act's member that holds the parts. */
  field: string;
  shape: string;
  fields?: FieldDescription[];
  parts?: PartDescription[];
  /** When the parts may be of several shapes, one entry per shape. */
  kinds?: NestedDescription[];
}

/** A record's durable name on the wire: its shape and the store's key for it. */
export interface Reference {
  shape: string;
  key: string | number;
}

/** A record as the API answers it: its reference and its members by name. */
export type VelleRecord = Reference & Record<string, unknown>;

export function isReference(value: unknown): value is Reference {
  return value !== null && typeof value === 'object' && !Array.isArray(value) && 'shape' in value && 'key' in value;
}

export function shapeNamed(model: ModelDescription, shapeName: string): ShapeDescription | undefined {
  return model.shapes.find(shape => shape.name === shapeName);
}

export function actNamed(model: ModelDescription, actName: string): ActDescription | undefined {
  return model.acts.find(act => act.name === actName);
}

/** A refinement over more than one shape: a record of it is a record of one of its bases. */
export function readsSeveralShapes(shape: ShapeDescription | undefined): boolean {
  return Boolean(shape && shape.kind === 'refinement' && shape.bases && shape.bases.length > 1);
}

/**
 * The member a shape's table is ordered by until the developer picks one: the first member the
 * description says a query may order by.
 */
export function defaultOrderBy(shape: ShapeDescription): string {
  const orderableMember = shape.members.find(member => member.orderable);
  return orderableMember ? orderableMember.name : '';
}

/** The refinements of this one shape that the record belongs to, each readable as a record of its own. */
export function refinementsHolding(model: ModelDescription, shapeName: string, record: VelleRecord): ShapeDescription[] {
  return model.shapes.filter(candidate =>
    candidate.kind === 'refinement'
      && candidate.bases !== undefined && candidate.bases.length === 1 && candidate.bases[0] === shapeName
      && record['is' + candidate.name] === true);
}
