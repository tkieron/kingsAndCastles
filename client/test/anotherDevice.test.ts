// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import qrcode from 'qrcode-generator';
import { formatCode, qrSvg, resumeLink, takeResumeFromAddress } from '../src/ui/AnotherDevice';

describe('another device', () => {
  it('shows a return code in two groups and accepts it typed any way', () => {
    expect(formatCode('K7MQ4QX2')).toBe('K7MQ-4QX2');
    expect(formatCode(' k7mq-4qx2 ')).toBe('K7MQ-4QX2');
    expect(formatCode('ABC')).toBe('ABC');
  });

  it('builds a link with the room and the code', () => {
    expect(resumeLink('123456', 'K7MQ4QX2', 'https://kc.pointsapp.pl/')).toBe('https://kc.pointsapp.pl/#room=123456&resume=K7MQ-4QX2');
  });

  it('takes the code out of the address at once, so it does not stay in the history', () => {
    history.replaceState(null, '', '/?lang=pl#room=123456&resume=K7MQ-4QX2');
    expect(takeResumeFromAddress()).toEqual({ room: '123456', code: 'K7MQ-4QX2' });
    expect(location.search).toBe('?lang=pl');
    expect(location.hash).toBe('');
    expect(takeResumeFromAddress()).toBeNull();
    history.replaceState(null, '', '/?room=123456');
    expect(takeResumeFromAddress()).toBeNull();
    expect(location.search).toBe('?room=123456');
  });

  it('draws a QR code that holds exactly the link', () => {
    const link = resumeLink('123456', 'K7MQ4QX2', 'https://kc.pointsapp.pl/');
    const svg = qrSvg(link);
    const qr = qrcode(0, 'M');
    qr.addData(link);
    qr.make();
    const n = qr.getModuleCount();
    let dark = 0;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (qr.isDark(y, x)) dark++;
    const d = svg.querySelector('path')!.getAttribute('d')!;
    expect(d.match(/M/g)!.length).toBe(dark);
    expect(svg.getAttribute('viewBox')).toBe(`0 0 ${n + 8} ${n + 8}`);
  });
});
