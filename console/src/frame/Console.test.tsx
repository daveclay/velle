import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { VelleClient } from '../api/VelleClient';
import { testModel } from '../model/testModel';
import { Console } from './Console';
import { storedRequestHeaders, STORED_HEADER_NAME, STORED_HEADER_VALUE } from './requestHeader';
import { activeTarget, routeOf } from './routes';

const client: VelleClient = {
  describeModel: async () => testModel,
  request: async <Data,>() => ({ page: { items: [], next: null }, count: 0, record: null, ticks: [] }) as Data,
};

afterEach(() => {
  cleanup();
  location.hash = '';
  localStorage.clear();
});

describe('the console', () => {
  it('lists the model in the sidebar and opens on the first shape', async () => {
    render(<Console client={client} ticksExposed={false} />);
    const firstShapeLink = await screen.findByRole('link', { name: 'Customer' });
    expect(firstShapeLink.getAttribute('href')).toBe('#shape/Customer');
    expect(firstShapeLink.className).toBe('active');
    expect(screen.getByRole('link', { name: 'BillCustomerby Staff' }).getAttribute('href')).toBe('#act/BillCustomer');
    expect(screen.getByRole('link', { name: 'SignUptransient' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Weekly' }).getAttribute('href')).toBe('#tick/Weekly');
    expect(await screen.findByText('No records.')).toBeTruthy();
  });

  it('shows the view the hash names', async () => {
    location.hash = '#act/SignUp';
    render(<Console client={client} ticksExposed={false} />);
    expect(await screen.findByText('A transient act: it leaves no record of its own.')).toBeTruthy();
  });

  it('says what the API answered when the model cannot be read', async () => {
    const failing: VelleClient = { ...client, describeModel: async () => { throw new Error('The API answered 401'); } };
    render(<Console client={failing} ticksExposed={false} />);
    expect(await screen.findByText('The API answered 401')).toBeTruthy();
  });
});

describe('the routes', () => {
  it('reads each hash, and a record lights up its shape', () => {
    expect(routeOf('#record/Invoice/a%2Fb', testModel)).toEqual({ view: 'record', shapeName: 'Invoice', recordKey: 'a/b' });
    expect(activeTarget(routeOf('#record/Invoice/1', testModel))).toBe('shape/Invoice');
    expect(routeOf('#tick/Weekly', testModel)).toEqual({ view: 'tick', schedule: 'Weekly' });
    expect(routeOf('#nothing', testModel)).toEqual({ view: 'none' });
  });
});

describe('the request header', () => {
  it('is sent only when both its name and its value are kept', () => {
    expect(storedRequestHeaders()).toEqual({});
    localStorage.setItem(STORED_HEADER_NAME, 'X-Velle-Actor');
    expect(storedRequestHeaders()).toEqual({});
    localStorage.setItem(STORED_HEADER_VALUE, 'Staff:1');
    expect(storedRequestHeaders()).toEqual({ 'X-Velle-Actor': 'Staff:1' });
  });
});
