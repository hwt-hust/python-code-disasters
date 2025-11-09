#exec() - Code injection vulnerability
def run_user_code(user_input):
    exec(user_input)

run_user_code("print('test')")
