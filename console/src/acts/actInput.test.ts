import { describe, expect, it } from 'vitest';
import { actNamed } from '../model/description';
import { testModel } from '../model/testModel';
import { actInput } from './actInput';

const billCustomer = actNamed(testModel, 'BillCustomer')!;
const signUp = actNamed(testModel, 'SignUp')!;

describe('what the developer typed, as the input the act\'s mutation takes', () => {
  it('leaves out a field left empty', () => {
    expect(actInput(testModel, signUp, { name: ' Ada ', email: '' })).toEqual({ name: 'Ada' });
  });

  it('sends a reference to one shape as its key', () => {
    expect(actInput(testModel, billCustomer, { customer: '7' })).toEqual({ customer: '7' });
    expect(actInput(testModel, billCustomer, { customer: 'Customer#7' })).toEqual({ customer: '7' });
  });

  it('sends a reference to a refinement with the shape the developer named', () => {
    expect(actInput(testModel, billCustomer, { payer: 'Company#3' })).toEqual({ payer: { shape: 'Company', key: '3' } });
    expect(actInput(testModel, billCustomer, { payer: '3' })).toEqual({ payer: { key: '3' } });
  });

  it('sends a reference that may be new as a key or as the number of the new record', () => {
    expect(actInput(testModel, billCustomer, { sibling: '4' })).toEqual({ sibling: { key: '4' } });
    expect(actInput(testModel, billCustomer, { sibling: '{"new": 1}' })).toEqual({ sibling: { new: 1 } });
  });

  it('reads values and references separated by commas', () => {
    expect(actInput(testModel, billCustomer, { copies: '1, 2', watchers: '5,6' }))
      .toEqual({ copies: [1, 2], watchers: ['5', '6'] });
  });

  it('sends a date as typed and a number as a number', () => {
    expect(actInput(testModel, billCustomer, { due: '2026-04-01' })).toEqual({ due: '2026-04-01' });
  });

  it('sends a field that may carry a new record as an existing key or as the JSON typed', () => {
    expect(actInput(testModel, billCustomer, { address: '9' })).toEqual({ address: { existing: { key: '9' } } });
    expect(actInput(testModel, billCustomer, { address: '{"newAddress": {"street": "Main"}}' }))
      .toEqual({ address: { newAddress: { street: 'Main' } } });
  });

  it('sends parts as the JSON list typed, and refuses JSON that does not parse', () => {
    expect(actInput(testModel, billCustomer, { lineItems: '[{"description": "Widget", "quantity": 2}]' }))
      .toEqual({ lineItems: [{ description: 'Widget', quantity: 2 }] });
    expect(() => actInput(testModel, billCustomer, { lineItems: '[{' })).toThrow();
  });
});
