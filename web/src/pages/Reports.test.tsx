import { toCsv } from './Reports';

describe('csv export', () => {
  it('quotes, neutralises formulas and joins arrays', () => {
    const csv = toCsv([{ a: '=cmd|calc', b: 'x,"y"', c: ['F1', 'F2'], d: null }], [
      { key: 'a', header: 'A' }, { key: 'b', header: 'B' }, { key: 'c', header: 'C' }, { key: 'd', header: 'D' }]);
    expect(csv).toBe('﻿A,B,C,D\r\n\'=cmd|calc,"x,""y""",F1; F2,');
  });
});
