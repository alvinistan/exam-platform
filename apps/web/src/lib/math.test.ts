import { escapeHtml, hasMath, parseFormula, scanFormulas, textRuns } from './math';
import { formulaProblem, latexError } from './mathCheck';

describe('formulas in question text', () => {
  it('finds inline and display formulas, and ignores dollar amounts', () => {
    expect(scanFormulas('Solve \\(x^2 = 4\\) then \\[\\frac{1}{2}\\]')).toEqual({
      formulas: [
        { latex: 'x^2 = 4', display: false },
        { latex: '\\frac{1}{2}', display: true },
      ],
      problem: null,
    });
    expect(scanFormulas('It costs $5 or $10.')).toEqual({ formulas: [], problem: null });
    expect(hasMath('It costs $5')).toBe(false);
    expect(hasMath('<p>\\(x\\)</p>')).toBe(true);
  });

  it('names delimiter problems', () => {
    expect(scanFormulas('\\(x^2').problem).toBe('A formula starting with \\( isn’t closed with \\).');
    expect(scanFormulas('x\\]').problem).toBe('A formula is closed with \\] but never opened.');
    expect(scanFormulas('\\(  \\)').problem).toBe('A formula is empty.');
  });

  it('reads HTML as the browser does, one run of text at a time', () => {
    expect(textRuns('<p>\\(x &lt; 3\\) <strong>and</strong> y</p>')).toEqual(['\\(x < 3\\) ', 'and', ' y']);
    expect(formulaProblem('<p>\\(x &lt; 3\\)</p>')).toBeNull();
    expect(formulaProblem('<p>\\(<strong>x</strong>\\)</p>')).toMatch(/isn’t closed/);
  });

  it('checks that KaTeX can typeset each formula, chemistry included', () => {
    expect(formulaProblem('<p>\\(\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}\\) and \\(\\ce{CaCO3 -> CaO + CO2}\\)</p>')).toBeNull();
    expect(formulaProblem('<p>\\(\\frac{1}{2\\)</p>')).toMatch(/^Formula “\\frac\{1\}\{2”: /);
    expect(latexError('\\notacommand{x}')).toMatch(/Undefined control sequence/);
    expect(formulaProblem('Area = \\(\\pi r^2\\)', false)).toBeNull();
  });

  it('does not allow links or HTML through formulas', () => {
    expect(latexError('\\href{https://evil.example}{x}')).toBe('\\href isn’t allowed in formulas.');
    expect(latexError('\\htmlClass{x}{y}')).toBe('\\htmlClass isn’t allowed in formulas.');
  });

  it('recognises a selected formula for editing', () => {
    expect(parseFormula(' \\(x^2\\) ')).toEqual({ latex: 'x^2', display: false });
    expect(parseFormula('\\[\\sum x\\]')).toEqual({ latex: '\\sum x', display: true });
    expect(parseFormula('x^2')).toBeNull();
    expect(escapeHtml('a < b & "c"')).toBe('a &lt; b &amp; &quot;c&quot;');
  });
});
