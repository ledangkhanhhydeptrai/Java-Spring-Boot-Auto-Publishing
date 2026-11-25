'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const script = fs.readFileSync(require('node:path').join(__dirname, '../extension/content.js'), 'utf8');

function makeEl(name, text = '') {
  const e = {
    tagName: name.toUpperCase(), innerText:text, textContent:text,
    attrs:{}, parentElement: null, children: [], childNodes:[{nodeType:3,textContent:text}],
    getClientRects: () => [1],
    getBoundingClientRect: () => ({width:540,height:450}),
    getAttribute(key){return this.attrs[key] ?? null},
    hasAttribute(key){return Object.hasOwn(this.attrs,key)},
    contains(other){return this === other || this.children.some(ch => ch.contains(other))},
    click(){this.clickCount=(this.clickCount||0)+1},
    focus(){}, dispatchEvent(){},
    querySelectorAll(){return []}
  };
  return e;
}
function attach(p,child){p.children.push(child);child.parentElement=p;return child;}

async function test(title, mode) {
  const composer=makeEl('div');
  const heading=attach(composer,makeEl(mode === 'divheading' ? 'div' : 'span','Tạo bài viết'));
  const publish=attach(composer,makeEl('button','Đăng'));
  const edit=attach(composer,makeEl('div',''));
  edit.attrs.contenteditable='true'; edit.attrs.role='textbox'; edit.isContentEditable=true;
  let fieldVisible = mode !== 'lazy';
  const placeholder=attach(composer,makeEl('span','Bạn viết gì đi...'));
  placeholder.click=()=>{ fieldVisible=true; };
  const root={body:{innerText:'Logged in'},querySelector(){return null},createRange:()=>({selectNodeContents(){}}),execCommand(cmd,flag,value){ if(cmd==='insertText') {edit.innerText=value;edit.textContent=value;return true;} return false; }};
  root.querySelectorAll=selector=>{
    if(selector.includes('[role="dialog"]'))return mode==='dialog'?[composer]:[];
    if(selector.includes('h1,h2,h3'))return [heading];
    if(selector.includes('[data-placeholder]'))return [placeholder];
    return [];
  };
  composer.querySelectorAll=selector=>{
    if(selector.includes('h1,h2,h3'))return [heading];
    if(selector.includes('contenteditable'))return fieldVisible?[edit]:[];
    if(selector.includes('button') || selector.includes('[role="button"]'))return [publish];
    if(selector.includes('data-placeholder') || selector.includes('p,span'))return [placeholder];
    return [];
  };
  const chrome={runtime:{onMessage:{addListener(fn){chrome.listener=fn}}}};
  const context={document:root,window:{getSelection:()=>({removeAllRanges(){},addRange(){}})},
    getComputedStyle:()=>({visibility:'visible'}),location:{hostname:'www.facebook.com',pathname:'/groups/1326570106277759'},
    chrome,InputEvent:class{},setTimeout,clearTimeout,Promise,Date,URL,console};
  vm.runInNewContext(script,context);
  const request={kind:'GROUP_FILL', preview:{id:'15e8de3f-cd4b-4ffb-96d0-ae5ec42a6c49',
    groupId:'1326570106277759',content:'Thử đăng SocialHub',
    marker:'[SocialHub Group Pilot 15e8de3f]',digest:'fixture'}};
  const response=await new Promise(resolve=>chrome.listener(request,null,resolve));
  assert.equal(response.ok,true,`Case ${mode}: ${response.error||''}`);
  assert.equal(edit.innerText,'Thử đăng SocialHub\n\n[SocialHub Group Pilot 15e8de3f]');
  assert.equal(publish.clickCount||0,0,'Must not click Publish during fill');
  console.log('PASS '+title);
}
(async()=>{
  await test('editor within role=dialog', 'dialog');
  await test('modal without dialog role', 'nodialog');
  await test('modal with DIV heading', 'divheading');
  await test('editor created after placeholder click', 'lazy');
})().catch(error=>{console.error(error);process.exitCode=1});
