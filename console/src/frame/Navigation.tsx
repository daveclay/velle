import type { ActDescription, ModelDescription } from '../model/description';
import { actTarget, shapeTarget, tickTarget } from './routes';

interface NavigationLinkProps {
  target: string;
  label: string;
  tags?: string[];
  active: string;
}

function NavigationLink({ target, label, tags = [], active }: NavigationLinkProps) {
  return (
    <a href={'#' + target} className={target === active ? 'active' : ''}>
      {label}
      {tags.map(tag => <span className="tag" key={tag}>{tag}</span>)}
    </a>
  );
}

function actTags(act: ActDescription): string[] {
  const tags: string[] = [];
  if (act.transient) tags.push('transient');
  if (act.actor) tags.push('by ' + act.actor.shape);
  return tags;
}

/** The sidebar's links: the model's shapes, refinements, acts, and schedules. */
export function Navigation({ model, active }: { model: ModelDescription; active: string }) {
  const shapes = model.shapes.filter(shape => shape.kind === 'shape');
  const refinements = model.shapes.filter(shape => shape.kind === 'refinement');
  return (
    <div>
      <h2>Shapes</h2>
      {shapes.map(shape => <NavigationLink key={shape.name} target={shapeTarget(shape.name)} label={shape.name} tags={shape.act ? ['act'] : []} active={active} />)}
      {refinements.length > 0 && <h2>Refinements</h2>}
      {refinements.map(refinement => <NavigationLink key={refinement.name} target={shapeTarget(refinement.name)} label={refinement.name} active={active} />)}
      <h2>Acts</h2>
      {model.acts.map(act => <NavigationLink key={act.name} target={actTarget(act.name)} label={act.name} tags={actTags(act)} active={active} />)}
      {model.schedules.length > 0 && <h2>Schedules</h2>}
      {model.schedules.map(schedule => <NavigationLink key={schedule} target={tickTarget(schedule)} label={schedule} active={active} />)}
    </div>
  );
}
