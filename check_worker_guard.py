from pathlib import Path
import sqlite3

s = (Path(__file__).resolve().parents[
         1] / 'backend/src/main/java/com/socialhub/publishingjob/engine/FanpageQueueProcessor.java').read_text()
a = s.index('AND (\n                                      (j.facebook_connection_id IS NOT NULL')
b = s.index('                                  )', s.index('                                      ))',
                                                           s.index('OR (j.facebook_connection_id IS NULL', a))) + len(
    '                                  )')
# extract by parenthesis balance instead of indentation
start = s.index('(', a);
depth = 0;
end = None
for i in range(start, len(s)):
    if s[i] == '(': depth += 1
    if s[i] == ')':
        depth -= 1
        if depth == 0: end = i + 1;break
predicate = s[start:end];
assert s.count(predicate) == 4
c = sqlite3.connect(':memory:');
c.create_function('BTRIM', 1, lambda x: x.strip() if x else x)
c.executescript('''create table social_accounts(id text, workspace_id text); create table facebook_page_connections(id text,social_account_id text,facebook_user_id text,status text,access_token_encrypted text,token_expires_at text); create table facebook_authorized_identities(workspace_id text,facebook_user_id text,health_status text,token_expires_at text); create table publishing_jobs(id text,social_account_id text,facebook_connection_id text);
insert into social_accounts values ('pageA','w'),('pageB','w'),('pageX','x');
insert into facebook_page_connections values ('grant1','pageA','user1','CONNECTED','cipher',null),('grant2','pageA','user2','CONNECTED','cipher',null),('grant3','pageB','user2','CONNECTED','cipher',null),('grantX','pageX','user1','CONNECTED','cipher',null);
insert into facebook_authorized_identities values ('w','user1','HEALTHY',null),('w','user2','HEALTHY',null),('x','user1','HEALTHY',null);
insert into publishing_jobs values ('job1','pageA','grant1'),('job2','pageA','grant2'),('job3','pageB','grant3'),('legacy','pageA',null),('wrong','pageA','grant3'),('otherWorkspace','pageX','grantX');''')
query = 'select j.id from publishing_jobs j join social_accounts a on a.id=j.social_account_id where ' + predicate
eligible = lambda: {x[0] for x in c.execute(query)}
assert eligible() == {'job1', 'job2', 'job3', 'legacy', 'otherWorkspace'};
print('PASS: healthy; wrong-Page binding denied')
c.execute("update facebook_page_connections set status='DISCONNECTED' where id='grant1'");
c.execute(
    "update facebook_authorized_identities set health_status='DISCONNECTED' where workspace_id='w' and facebook_user_id='user1'");
assert eligible() == {'job2', 'job3', 'otherWorkspace'};
print('PASS: disconnect isolates bound grant, preserves other grant and workspace; ambiguous legacy held')
c.execute("update facebook_page_connections set status='CONNECTED' where id='grant1'");
c.execute(
    "update facebook_authorized_identities set health_status='CHECK_ERROR' where workspace_id='w' and facebook_user_id='user1'");
assert 'job1' in eligible();
print('PASS: transient failure does not revoke grant')
c.execute("update facebook_page_connections set status='PERMISSION_BLOCKED' where id='grant1'");
assert eligible() == {'job2', 'job3', 'otherWorkspace'};
print('PASS: missing Page permission isolates exact grant')
c.execute("update facebook_page_connections set status='CONNECTED' where id='grant1'");
c.execute(
    "update facebook_authorized_identities set health_status='REAUTH_REQUIRED' where workspace_id='w' and facebook_user_id='user1'");
assert eligible() == {'job2', 'job3', 'otherWorkspace'};
print('PASS: rejected token blocks even if connection status stale')
c.execute(
    "update facebook_authorized_identities set health_status='HEALTHY',token_expires_at='2000-01-01' where workspace_id='w' and facebook_user_id='user1'");
assert eligible() == {'job2', 'job3', 'otherWorkspace'};
print('PASS: known token expiry blocks')
c.execute(
    "update facebook_authorized_identities set token_expires_at=null where workspace_id='w' and facebook_user_id='user1'");
assert 'job1' in eligible();
print('PASS: explicit restoration can release otherwise eligible jobs')
c.execute("update facebook_page_connections set access_token_encrypted='' where id='grant1'");
assert 'job1' not in eligible() and 'job2' in eligible();
print('PASS: missing token denied, unrelated grant survives')
print('8 scenarios, 4 identical worker guards; SQLite predicate test only, not PostgreSQL integration.')
