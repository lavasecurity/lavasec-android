// These reference hashes come from iOS-internal Git objects, not the Android manifest.
// Regenerating provenance cannot silently bless an older shared UI composition.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),ts=require('typescript');
const reference=require('./shared-ui-reference.json');
const source=file=>fs.readFileSync(path.join(__dirname,'../canonical',file),'utf8');
const parse=text=>ts.createSourceFile('source.tsx',text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
const hash=text=>crypto.createHash('sha256').update(text).digest('hex');
function withoutImports(text){
  const ast=parse(text);
  for(const node of [...ast.statements].reverse())if(ts.isImportDeclaration(node))text=text.slice(0,node.pos)+text.slice(node.end);
  return text.trim();
}
test('the shared UI reference and canonical provenance use the same reviewed upstream revision',()=>{
  expect(require('../scripts/canonical-source.manifest.json').canonical.revision).toBe(reference.revision);
});
test.each(Object.entries(reference.files))('%s keeps the complete latest shared body', (file,expected)=>{
  let text=source(file);
  if(file==='review/ActivityScreen.tsx'){
    // Android's native chart producer only supports total. Pin this one capability
    // guard explicitly; every other byte of the shared screen body remains pinned.
    const nativeGuard="NativeReview.chooseActivityExport(!!app&&loaded&&!!dates&&chart==='total')";
    expect(text.split(nativeGuard)).toHaveLength(2);
    text=text.replace(nativeGuard,'NativeReview.chooseActivityExport(!!app&&loaded&&!!dates)');
  }
  expect(hash(withoutImports(text))).toBe(expected);
});
test.each(Object.entries(reference.functions))('%s keeps the current shared screen composition',(identity,expected)=>{
  const [file,name]=identity.split('#'),ast=parse(source(file));
  const node=ast.statements.find(item=>ts.isFunctionDeclaration(item)&&item.name?.text===name);
  expect(node).toBeDefined();
  const body=identity==='review/DiagnosticScreens.tsx#StatsScreen'?node.getText(ast).replace('  const s=useWindowStyles(base_s);\n',''):node.getText(ast);
  expect(hash(body)).toBe(expected);
});
test('Guard retains the exact upstream bottom group with its two shared destinations',()=>{
  const ast=parse(source('review/screens.tsx')),groups=[];
  function visit(node){
    if(ts.isJsxElement(node)&&node.openingElement.tagName.getText(ast)==='Group'&&node.openingElement.getText(ast).includes('testID="guard.tools"'))groups.push(node);
    ts.forEachChild(node,visit);
  }
  visit(ast);expect(groups).toHaveLength(1);expect(hash(groups[0].getText(ast))).toBe(reference.guardRows);
});

test.each(Object.entries(reference.calls))('%s retains its exact upstream toolbar declaration',(identity,expected)=>{
  const [file,label]=identity.split('#'),ast=parse(source(file)),calls=[];
  function visit(node){
    if(ts.isCallExpression(node)&&node.expression.getText(ast)==='toolbarAction'&&node.arguments[0]?.text===label)calls.push(node);
    ts.forEachChild(node,visit);
  }
  visit(ast);expect(calls).toHaveLength(1);expect(hash(calls[0].getText(ast))).toBe(expected);
});
